package me.kismeria.smpcore.feature;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import me.kismeria.smpcore.SmpCore;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Автовход для лицензий на пиратском сервере (как FastLogin). Если ник есть у Mojang, сервер для этого
 * игрока включает обычную онлайн-проверку: лицензия заходит без пароля, пиратка — не проходит.
 * Ник, хоть раз зашедший с лицензией, закрепляется: дальше под ним пускают только лицензию.
 * <p>
 * В Paper нет API для проверки отдельного игрока, поэтому перехватываем приветственный пакет в Netty
 * и запускаем ту же ветку, что ванилла запускает при online-mode=true (дальше всё делает сервер сам).
 */
public final class PremiumLogin implements Listener {

    private static final Key LISTENER_KEY = Key.key("smpcore", "premium");
    private static final String HANDLER = "smpcore_premium";
    private static final String HELLO = "net.minecraft.network.protocol.login.ServerboundHelloPacket";
    private static final String LOGIN_LISTENER = "net.minecraft.server.network.ServerLoginPacketListenerImpl";
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final long RETRY_WINDOW_MS = 5 * 60_000L;
    private static final long LOOKUP_TTL_MS = 24 * 3_600_000L;

    private record Attempt(String ip, long at) {
    }

    private record Lookup(boolean exists, long at) {
    }

    private final SmpCore plugin;
    private final File file;
    private final YamlConfiguration data;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ExecutorService lookups = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SmpCore premium lookup");
        thread.setDaemon(true);
        return thread;
    });
    /** Запущена онлайн-проверка: ник → с какого IP и когда. */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Map<String, Lookup> mojang = new ConcurrentHashMap<>();
    /** Прошли проверку в этом входе — окно пароля не показываем. */
    private final Set<String> verified = ConcurrentHashMap.newKeySet();
    private boolean installed;

    public PremiumLogin(SmpCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "premium.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    // ---------------------------------------------------------------- установка

    public void enable() {
        try {
            Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
            Class<?> listener = Class.forName("io.papermc.paper.network.ChannelInitializeListener");
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "afterInitChannel" -> {
                    inject((Channel) args[0]);
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "SmpCore premium login";
                default -> null;
            };
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, handler);
            holder.getMethod("addListener", Key.class, listener).invoke(null, LISTENER_KEY, proxy);
            installed = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Автовход для лицензий недоступен: " + e);
        }
    }

    public void disable() {
        if (!installed) {
            return;
        }
        try {
            Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
            holder.getMethod("removeListener", Key.class).invoke(null, LISTENER_KEY);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // сервер выключается
        }
        lookups.shutdownNow();
    }

    private void inject(Channel channel) {
        if (channel.pipeline().get("packet_handler") != null && channel.pipeline().get(HANDLER) == null) {
            channel.pipeline().addBefore("packet_handler", HANDLER, new HelloHandler());
        }
    }

    // ---------------------------------------------------------------- перехват входа

    private final class HelloHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!msg.getClass().getName().equals(HELLO)) {
                super.channelRead(ctx, msg);
                return;
            }
            Channel channel = ctx.channel();
            if (!plugin.flag("auth.premium.enabled") || Bukkit.getOnlineMode() || fromFloodgate(channel)) {
                pass(ctx, msg);
                return;
            }
            String name = (String) msg.getClass().getMethod("name").invoke(msg);
            String ip = channel.remoteAddress() instanceof InetSocketAddress address && address.getAddress() != null
                    ? address.getAddress().getHostAddress() : "?";
            // запрос к Mojang — не на сетевом потоке
            lookups.execute(() -> {
                boolean premium = decide(name, ip);
                channel.eventLoop().execute(() -> {
                    if (!channel.isActive()) {
                        return;
                    }
                    if (premium) {
                        try {
                            requestAuthentication(channel, msg, name, ip);
                            ctx.pipeline().remove(this);
                            return;
                        } catch (ReflectiveOperationException | RuntimeException e) {
                            plugin.getLogger().warning("Не удалось включить проверку лицензии для " + name + ": " + e);
                        }
                    }
                    pass(ctx, msg);
                });
            });
        }

        private void pass(ChannelHandlerContext ctx, Object msg) {
            ctx.fireChannelRead(msg);
            if (ctx.pipeline().get(HANDLER) != null) {
                ctx.pipeline().remove(this);
            }
        }
    }

    private static boolean fromFloodgate(Channel channel) {
        if (!AttributeKey.exists("floodgate-player")) {
            return false;
        }
        AttributeKey<Object> key = AttributeKey.valueOf("floodgate-player");
        return channel.hasAttr(key) && channel.attr(key).get() != null;
    }

    /** @return true — проверять лицензию, false — обычный пиратский вход (с паролем) */
    private boolean decide(String name, String ip) {
        if (!VALID_NAME.matcher(name).matches()) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (isLocked(key)) {
            return true;
        }
        Attempt last = attempts.remove(key);
        if (last != null && last.ip().equals(ip) && System.currentTimeMillis() - last.at() < RETRY_WINDOW_MS
                && plugin.flag("auth.premium.cracked-fallback")) {
            // прошлая попытка не прошла проверку — это пиратка с ником, занятым лицензией: пускаем с паролем
            plugin.getLogger().info(name + " не прошёл проверку лицензии — вход с паролем.");
            return false;
        }
        return existsAtMojang(name);
    }

    private boolean existsAtMojang(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Lookup cached = mojang.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < LOOKUP_TTL_MS) {
            return cached.exists();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                    .timeout(Duration.ofSeconds(5)).header("User-Agent", "SmpCore").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean exists = response.statusCode() == 200 && response.body().contains("\"id\"");
            if (response.statusCode() == 200 || response.statusCode() == 204 || response.statusCode() == 404) {
                mojang.put(key, new Lookup(exists, now));
            }
            return exists;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** То же, что делает ServerLoginPacketListenerImpl.handleHello при online-mode=true. */
    private void requestAuthentication(Channel channel, Object hello, String name, String ip) throws ReflectiveOperationException {
        Object connection = channel.pipeline().get("packet_handler");
        Object listener = connection.getClass().getMethod("getPacketListener").invoke(connection);
        Class<?> listenerType = listener.getClass();
        if (!listenerType.getName().equals(LOGIN_LISTENER)) {
            throw new IllegalStateException("unexpected listener " + listenerType.getName());
        }
        ClassLoader loader = listenerType.getClassLoader();
        field(listenerType, "requestedUuid").set(listener, hello.getClass().getMethod("profileId").invoke(hello));
        field(listenerType, "requestedUsername").set(listener, name);
        Object server = field(listenerType, "server").get(listener);
        byte[] challenge = (byte[]) field(listenerType, "challenge").get(listener);
        KeyPair keyPair = (KeyPair) server.getClass().getMethod("getKeyPair").invoke(server);

        Class<?> stateType = Class.forName(LOGIN_LISTENER + "$State", false, loader);
        Object keyState = stateType.getMethod("valueOf", String.class).invoke(null, "KEY");
        field(listenerType, "state").set(listener, keyState);

        Class<?> packetType = Class.forName("net.minecraft.network.protocol.Packet", false, loader);
        Object request = Class.forName("net.minecraft.network.protocol.login.ClientboundHelloPacket", false, loader)
                .getConstructor(String.class, byte[].class, byte[].class, boolean.class)
                .newInstance("", keyPair.getPublic().getEncoded(), challenge, true);
        attempts.put(name.toLowerCase(Locale.ROOT), new Attempt(ip, System.currentTimeMillis()));
        connection.getClass().getMethod("send", packetType).invoke(connection, request);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    // ---------------------------------------------------------------- после проверки

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String key = event.getName().toLowerCase(Locale.ROOT);
        Attempt attempt = attempts.remove(key);
        // сюда доходит только прошедший онлайн-проверку (иначе сервер отключает «Failed to verify username»)
        if (attempt == null || !event.getPlayerProfile().hasTextures()) {
            return;
        }
        verified.add(key);
        if (!isLocked(key)) {
            lock(key, event.getName());
        }
        if (plugin.flag("auth.premium.keep-offline-uuid")) {
            // вещи и прогресс лежат под пиратским UUID — оставляем его, чтобы ничего не потерять
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + event.getName()).getBytes(StandardCharsets.UTF_8));
            PlayerProfile old = event.getPlayerProfile();
            PlayerProfile profile = Bukkit.createProfile(offline, event.getName());
            profile.setProperties(old.getProperties());
            event.setPlayerProfile(profile);
        }
        plugin.getLogger().info(event.getName() + " вошёл с лицензией — без пароля.");
    }

    /** Для AuthManager: этот вход подтверждён лицензией (одноразово). */
    public boolean consumeVerified(String name) {
        return verified.remove(name.toLowerCase(Locale.ROOT));
    }

    private boolean isLocked(String key) {
        synchronized (data) {
            return data.getBoolean("locked." + key + ".premium");
        }
    }

    private void lock(String key, String name) {
        synchronized (data) {
            data.set("locked." + key + ".premium", true);
            data.set("locked." + key + ".name", name);
            data.set("locked." + key + ".since", System.currentTimeMillis());
            try {
                data.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Не удалось сохранить premium.yml: " + e.getMessage());
            }
        }
        // если под этим ником раньше регистрировалась пиратка — её пароль больше не нужен
        plugin.auth().forget(name);
        plugin.getLogger().info("Ник " + name + " закреплён за лицензией.");
    }
}
