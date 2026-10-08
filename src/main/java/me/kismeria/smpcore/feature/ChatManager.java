package me.kismeria.smpcore.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.PlatformIcon;
import me.kismeria.smpcore.util.Text;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Глобальный чат со своим форматом и кнопкой перевода [⇄] (как в FlectonePulse, но проще). */
public final class ChatManager implements Listener, CommandExecutor {

    private static final int HISTORY = 500;

    private final SmpCore plugin;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, String> history = Collections.synchronizedMap(new LinkedHashMap<>(HISTORY, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
            return size() > HISTORY;
        }
    });
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ChatManager(SmpCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.flag("chat.enabled")) {
            return;
        }
        Player sender = event.getPlayer();
        // чат только глобальный: сообщение видят все
        try {
            event.viewers().addAll(Bukkit.getOnlinePlayers());
        } catch (UnsupportedOperationException ignored) {
            // другой плагин сделал список неизменяемым — оставляем как есть
        }
        int id = ids.incrementAndGet();
        history.put(id, Text.plain(event.message()));
        boolean hideName = plugin.invisibility().hideInChat(sender);
        boolean translate = plugin.flag("chat.translate");
        String format = plugin.getConfig().getString("chat.format", "<player> » <message>");

        event.renderer((source, displayName, message, viewer) -> {
            CommandSender reader = viewer instanceof CommandSender cs ? cs : Bukkit.getConsoleSender();
            // иконка платформы: Java-читателю — спрайт блока, бедрокеру — цветной значок
            Component name = hideName ? plugin.lang().get(reader, "invisibility.hidden-name")
                    : PlatformIcon.of(plugin, sender, PlatformIcon.isBedrock(viewer)).append(displayName);
            Component line = Text.mm(format, Lang.comp("player", name), Lang.comp("message", message));
            if (translate && viewer instanceof Player player && !player.getUniqueId().equals(source.getUniqueId())) {
                line = line.append(button(player, id));
            }
            return line;
        });
    }

    /** Сообщения «X зашёл» скрыты. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.flag("chat.hide-join-messages")) {
            event.joinMessage(null);
        }
    }

    /** Сообщения «X вышел» скрыты. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.flag("chat.hide-quit-messages")) {
            event.quitMessage(null);
        }
    }

    private Component button(Audience viewer, int id) {
        CommandSender reader = (CommandSender) viewer;
        return plugin.lang().get(reader, "chat.translate-button")
                .hoverEvent(HoverEvent.showText(plugin.lang().get(reader, "chat.translate-hover")))
                .clickEvent(ClickEvent.runCommand("/smptranslate " + id));
    }

    // ---------------------------------------------------------------- перевод

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        int id;
        try {
            id = Integer.parseInt(args.length > 0 ? args[0] : "");
        } catch (NumberFormatException e) {
            plugin.lang().send(player, "command.translate-usage");
            return true;
        }
        String text = history.get(id);
        if (text == null) {
            plugin.lang().send(player, "chat.translate-expired");
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = lastRequest.put(player.getUniqueId(), now);
        if (last != null && now - last < 1000) {
            return true;
        }
        String locale = plugin.lang().localeOf(player);
        String target = locale.contains("_") ? locale.substring(0, locale.indexOf('_')) : locale;
        String cached = cache.get(id + ":" + target);
        if (cached != null) {
            deliver(player, cached);
            return true;
        }
        String tl = URLEncoder.encode(target, StandardCharsets.UTF_8);
        String q = URLEncoder.encode(text, StandardCharsets.UTF_8);
        // gtx Google часто режет с IP серверов («automated queries») — сначала endpoint расширения Chrome
        request("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=" + tl + "&q=" + q,
                body -> parseDict(body, target))
                .exceptionallyCompose(e -> request("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl="
                        + tl + "&q=" + q, body -> parseGtx(body, target)))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        plugin.getLogger().warning("Перевод не удался: " + error.getMessage());
                        plugin.lang().send(player, "chat.translate-failed");
                        return;
                    }
                    cache.put(id + ":" + target, result);
                    deliver(player, result);
                });
        return true;
    }

    private CompletableFuture<String> request(String url, Function<String, String> parser) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Mozilla/5.0").GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return parser.apply(response.body());
        });
    }

    /** Ответ dict-chrome-ex: [["текст","ru"]] (или ["текст"]). */
    private static String parseDict(String body, String target) {
        JsonArray root = JsonParser.parseString(body).getAsJsonArray();
        JsonElement first = root.get(0);
        if (first.isJsonArray()) {
            JsonArray pair = first.getAsJsonArray();
            return result(pair.size() > 1 ? pair.get(1).getAsString() : "?", target, pair.get(0).getAsString());
        }
        return result("?", target, first.getAsString());
    }

    /** Ответ gtx: [[["текст","исходник",...],...],null,"ru",...]. */
    private static String parseGtx(String body, String target) {
        JsonArray root = JsonParser.parseString(body).getAsJsonArray();
        StringBuilder text = new StringBuilder();
        for (JsonElement segment : root.get(0).getAsJsonArray()) {
            JsonElement part = segment.getAsJsonArray().get(0);
            if (!part.isJsonNull()) {
                text.append(part.getAsString());
            }
        }
        String from = root.size() > 2 && !root.get(2).isJsonNull() ? root.get(2).getAsString() : "?";
        return result(from, target, text.toString());
    }

    /** @return "from\ntarget\ntext" или "" если язык уже совпадает */
    private static String result(String from, String target, String text) {
        if (from.toLowerCase(Locale.ROOT).startsWith(target.toLowerCase(Locale.ROOT))) {
            return "";
        }
        return from + "\n" + target + "\n" + text;
    }

    private void deliver(Player player, String result) {
        if (result.isEmpty()) {
            plugin.lang().send(player, "chat.translate-same");
            return;
        }
        String[] parts = result.split("\n", 3);
        player.sendMessage(plugin.lang().get(player, "chat.translated",
                Lang.txt("from", parts[0]), Lang.txt("to", parts[1]), Lang.txt("text", parts[2])));
    }
}
