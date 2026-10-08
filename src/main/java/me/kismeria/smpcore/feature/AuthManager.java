package me.kismeria.smpcore.feature;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Bedrock;
import me.kismeria.smpcore.util.Text;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Вход по паролю для пиратского сервера: окно (Dialog API) показывается ещё на загрузке,
 * в мир игрок попадает только после верного пароля. Bedrock через Floodgate — без пароля.
 * Пароли хранятся только хешем PBKDF2 с солью в auth.yml.
 */
public final class AuthManager implements Listener, TabExecutor {

    private static final Key SUBMIT = Key.key("smpcore", "auth/submit");
    private static final Key LEAVE = Key.key("smpcore", "auth/leave");
    private static final Key CHANGE = Key.key("smpcore", "auth/change");
    private static final int ITERATIONS = 120_000;
    private static final long IP_WINDOW_MS = 10 * 60_000L;

    private final SmpCore plugin;
    private final File file;
    private final YamlConfiguration accounts;
    private final SecureRandom random = new SecureRandom();
    /** Игроки на загрузке, ждём их ответ в окне. null — нажали «Выйти». */
    private final Map<UUID, CompletableFuture<DialogResponseView>> waiting = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> ipFails = new ConcurrentHashMap<>();
    private final Set<UUID> justRegistered = ConcurrentHashMap.newKeySet();

    public AuthManager(SmpCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auth.yml");
        this.accounts = YamlConfiguration.loadConfiguration(file);
    }

    // ---------------------------------------------------------------- вход

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        if (!plugin.flag("auth.enabled")) {
            return;
        }
        PlayerConfigurationConnection connection = event.getConnection();
        PlayerProfile profile = connection.getProfile();
        String name = profile.getName();
        UUID id = profile.getId();
        if (name == null || id == null || Bedrock.is(id)) {
            return;
        }
        if (Bukkit.getOnlineMode() && plugin.flag("auth.skip-online-mode")) {
            return;
        }
        // лицензия, подтверждённая Mojang (автовход), — без пароля
        if (plugin.premium() != null && plugin.premium().consumeVerified(name)) {
            return;
        }
        String locale = locale(connection);
        String ip = ip(connection);
        if (ipBlocked(ip)) {
            connection.disconnect(text(locale, "auth.kick-ip"));
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        String hash;
        synchronized (accounts) {
            hash = accounts.getString(key + ".hash");
            long sessionMs = Math.max(0, plugin.getConfig().getInt("auth.session-hours", 12)) * 3_600_000L;
            if (hash != null && ip.equals(accounts.getString(key + ".ip"))
                    && System.currentTimeMillis() - accounts.getLong(key + ".last") < sessionMs) {
                return;
            }
        }

        boolean register = hash == null;
        int maxAttempts = Math.max(1, plugin.getConfig().getInt("auth.max-attempts", 3));
        int minLength = Math.max(1, plugin.getConfig().getInt("auth.min-length", 4));
        long deadline = System.currentTimeMillis() + Math.max(15, plugin.getConfig().getInt("auth.timeout-seconds", 60)) * 1000L;
        int attempts = 0;
        Component error = null;
        while (true) {
            CompletableFuture<DialogResponseView> future = new CompletableFuture<>();
            waiting.put(id, future);
            connection.getAudience().showDialog(register ? registerDialog(locale, name, error) : loginDialog(locale, name, error));
            DialogResponseView response;
            try {
                response = await(connection, future, deadline);
            } catch (TimeoutException e) {
                connection.disconnect(text(locale, "auth.kick-timeout"));
                return;
            } finally {
                waiting.remove(id);
            }
            if (!connection.isConnected()) {
                return;
            }
            if (response == null) {
                connection.disconnect(text(locale, "auth.kick-left"));
                return;
            }
            String password = response.getText("password");
            password = password == null ? "" : password;
            if (register) {
                String repeat = response.getText("repeat");
                if (password.length() < minLength) {
                    error = text(locale, "auth.too-short", Lang.ph("min", minLength));
                    continue;
                }
                if (!password.equals(repeat)) {
                    error = text(locale, "auth.mismatch");
                    continue;
                }
                synchronized (accounts) {
                    accounts.set(key + ".name", name);
                    accounts.set(key + ".hash", hash(password));
                    accounts.set(key + ".registered", System.currentTimeMillis());
                    remember(key, ip);
                }
                justRegistered.add(id);
                plugin.getLogger().info(name + " зарегистрировался.");
                break;
            }
            if (verify(password, hash)) {
                synchronized (accounts) {
                    remember(key, ip);
                }
                break;
            }
            attempts++;
            failFrom(ip);
            plugin.getLogger().warning("Неверный пароль для " + name + " с " + ip + " (" + attempts + "/" + maxAttempts + ")");
            if (attempts >= maxAttempts || ipBlocked(ip)) {
                connection.disconnect(text(locale, "auth.kick-wrong"));
                return;
            }
            error = text(locale, "auth.wrong", Lang.ph("left", maxAttempts - attempts));
        }
        connection.getAudience().closeDialog();
    }

    /** Ждём ответ, пока игрок на связи и не вышло время. */
    private static DialogResponseView await(PlayerConfigurationConnection connection, CompletableFuture<DialogResponseView> future,
                                            long deadline) throws TimeoutException {
        while (true) {
            if (!connection.isConnected()) {
                return null;
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                throw new TimeoutException();
            }
            try {
                return future.get(Math.min(left, 1000), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // проверим связь и ждём дальше
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                return null;
            }
        }
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        Key key = event.getIdentifier();
        PlayerCommonConnection common = event.getCommonConnection();
        if (common instanceof PlayerConfigurationConnection connection && (key.equals(SUBMIT) || key.equals(LEAVE))) {
            CompletableFuture<DialogResponseView> future = waiting.get(connection.getProfile().getId());
            if (future != null) {
                future.complete(key.equals(SUBMIT) ? event.getDialogResponseView() : null);
            }
        } else if (common instanceof PlayerGameConnection game && key.equals(CHANGE)) {
            Player player = game.getPlayer();
            DialogResponseView view = event.getDialogResponseView();
            if (view != null) {
                Bukkit.getScheduler().runTask(plugin, () -> change(player, view));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (justRegistered.remove(event.getPlayer().getUniqueId())) {
            plugin.lang().send(event.getPlayer(), "auth.registered");
        }
    }

    // ---------------------------------------------------------------- окна

    private Dialog registerDialog(String locale, String name, Component error) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text(locale, "auth.register-text", Lang.txt("player", name)), 300));
        body.add(DialogBody.plainMessage(text(locale, "auth.visible-warning"), 300));
        if (error != null) {
            body.add(DialogBody.plainMessage(error, 300));
        }
        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(text(locale, "auth.register-title"))
                        .canCloseWithEscape(false)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .body(body)
                        .inputs(List.of(
                                DialogInput.text("password", text(locale, "auth.password")).maxLength(64).width(250).build(),
                                DialogInput.text("repeat", text(locale, "auth.repeat")).maxLength(64).width(250).build()))
                        .build())
                .type(buttons(locale, "auth.register-button")));
    }

    private Dialog loginDialog(String locale, String name, Component error) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text(locale, "auth.login-text", Lang.txt("player", name)), 300));
        if (error != null) {
            body.add(DialogBody.plainMessage(error, 300));
        }
        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(text(locale, "auth.login-title"))
                        .canCloseWithEscape(false)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .body(body)
                        .inputs(List.of(DialogInput.text("password", text(locale, "auth.password")).maxLength(64).width(250).build()))
                        .build())
                .type(buttons(locale, "auth.login-button")));
    }

    private DialogType buttons(String locale, String submitKey) {
        return DialogType.confirmation(
                ActionButton.builder(text(locale, submitKey)).width(120).action(DialogAction.customClick(SUBMIT, null)).build(),
                ActionButton.builder(text(locale, "auth.leave-button")).width(120).action(DialogAction.customClick(LEAVE, null)).build());
    }

    // ---------------------------------------------------------------- смена пароля

    private void openChange(Player player) {
        String locale = plugin.lang().localeOf(player);
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(text(locale, "auth.change-title"))
                        .body(List.of(DialogBody.plainMessage(text(locale, "auth.visible-warning"), 300)))
                        .inputs(List.of(
                                DialogInput.text("old", text(locale, "auth.old")).maxLength(64).width(250).build(),
                                DialogInput.text("password", text(locale, "auth.new")).maxLength(64).width(250).build(),
                                DialogInput.text("repeat", text(locale, "auth.repeat")).maxLength(64).width(250).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(text(locale, "auth.change-button")).width(120).action(DialogAction.customClick(CHANGE, null)).build(),
                        ActionButton.builder(text(locale, "auth.cancel-button")).width(120).build())));
        player.showDialog(dialog);
    }

    private void change(Player player, DialogResponseView view) {
        String key = player.getName().toLowerCase(Locale.ROOT);
        String hash;
        synchronized (accounts) {
            hash = accounts.getString(key + ".hash");
        }
        if (hash == null) {
            plugin.lang().send(player, "auth.no-account");
            return;
        }
        String old = view.getText("old");
        String password = view.getText("password");
        int minLength = Math.max(1, plugin.getConfig().getInt("auth.min-length", 4));
        if (old == null || !verify(old, hash)) {
            plugin.lang().send(player, "auth.change-wrong");
            return;
        }
        if (password == null || password.length() < minLength) {
            plugin.lang().send(player, "auth.too-short", Lang.ph("min", minLength));
            return;
        }
        if (!password.equals(view.getText("repeat"))) {
            plugin.lang().send(player, "auth.mismatch");
            return;
        }
        synchronized (accounts) {
            accounts.set(key + ".hash", hash(password));
            save();
        }
        plugin.lang().send(player, "auth.changed");
    }

    // ---------------------------------------------------------------- команды

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("changepassword")) {
            if (!(sender instanceof Player player)) {
                plugin.lang().send(sender, "invsee.only-players");
            } else if (Bedrock.is(player.getUniqueId())) {
                plugin.lang().send(player, "auth.no-account");
            } else {
                openChange(player);
            }
            return true;
        }
        // /resetpassword <ник>
        if (args.length == 0) {
            plugin.lang().send(sender, "auth.reset-usage");
            return true;
        }
        String key = args[0].toLowerCase(Locale.ROOT);
        boolean existed;
        synchronized (accounts) {
            existed = accounts.contains(key);
            accounts.set(key, null);
            save();
        }
        plugin.lang().send(sender, existed ? "auth.reset-done" : "auth.reset-none", Lang.txt("player", args[0]));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("resetpassword") || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        synchronized (accounts) {
            return accounts.getKeys(false).stream()
                    .map(k -> accounts.getString(k + ".name", k))
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
    }

    // ---------------------------------------------------------------- хранение и защита

    /** Удалить пароль ника (ник закрепили за лицензией). */
    public void forget(String name) {
        synchronized (accounts) {
            String key = name.toLowerCase(Locale.ROOT);
            if (accounts.contains(key)) {
                accounts.set(key, null);
                save();
            }
        }
    }

    private void remember(String key, String ip) {
        accounts.set(key + ".ip", ip);
        accounts.set(key + ".last", System.currentTimeMillis());
        save();
    }

    private void save() {
        try {
            accounts.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить auth.yml: " + e.getMessage());
        }
    }

    private String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    private static boolean verify(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        return MessageDigest.isEqual(expected, derive(password, salt, Integer.parseInt(parts[1])));
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void failFrom(String ip) {
        Deque<Long> fails = ipFails.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (fails) {
            fails.addLast(System.currentTimeMillis());
        }
    }

    private boolean ipBlocked(String ip) {
        Deque<Long> fails = ipFails.get(ip);
        if (fails == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (fails) {
            while (!fails.isEmpty() && now - fails.peekFirst() > IP_WINDOW_MS) {
                fails.removeFirst();
            }
            return fails.size() >= Math.max(1, plugin.getConfig().getInt("auth.ip-max-fails", 5));
        }
    }

    private static String ip(PlayerConfigurationConnection connection) {
        InetSocketAddress address = connection.getClientAddress();
        return address != null && address.getAddress() != null ? address.getAddress().getHostAddress() : "?";
    }

    private String locale(PlayerConfigurationConnection connection) {
        try {
            String locale = connection.getClientOption(ClientOption.LOCALE);
            return locale != null ? locale.toLowerCase(Locale.ROOT) : plugin.lang().fallback();
        } catch (RuntimeException e) {
            return plugin.lang().fallback();
        }
    }

    private Component text(String locale, String key, TagResolver... resolvers) {
        return Text.mm(plugin.lang().raw(locale, key), resolvers);
    }
}
