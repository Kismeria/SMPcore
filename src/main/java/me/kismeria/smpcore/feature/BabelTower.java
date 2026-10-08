package me.kismeria.smpcore.feature;

import com.google.gson.JsonObject;
import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.StateStore.Phase;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ачивка «Вавилонская башня»: двое рядом говорят в Simple Voice Chat друг за другом,
 * а языки их клиентов разные (ru и en и т.д.). Голос не распознаётся — язык берётся из настроек клиента.
 */
public final class BabelTower implements Listener {

    private static final String CRITERION = "babel";
    private static final long CHECK_EVERY_MS = 500;

    private final SmpCore plugin;
    private final NamespacedKey key;
    private final Map<UUID, Long> lastSpoke = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCheck = new ConcurrentHashMap<>();
    private Advancement advancement;

    public BabelTower(SmpCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "babel_tower");
    }

    /** Регистрирует ачивку (вкладка «Приключения»). Текст — из основного языка. */
    public void register() {
        advancement = Bukkit.getAdvancement(key);
        if (advancement != null) {
            return;
        }
        // translate-ключ: Paper подставит текст из lang/<язык клиента>.yml при отправке
        String locale = plugin.lang().fallback();
        JsonObject title = new JsonObject();
        title.addProperty("translate", Lang.CLIENT_PREFIX + "advancement.babel-title");
        title.addProperty("fallback", plugin.lang().raw(locale, "advancement.babel-title"));
        JsonObject description = new JsonObject();
        description.addProperty("translate", Lang.CLIENT_PREFIX + "advancement.babel-description");
        description.addProperty("fallback", plugin.lang().raw(locale, "advancement.babel-description"));
        JsonObject icon = new JsonObject();
        icon.addProperty("id", "minecraft:bookshelf");

        JsonObject display = new JsonObject();
        display.add("icon", icon);
        display.add("title", title);
        display.add("description", description);
        display.addProperty("frame", "challenge");
        display.addProperty("show_toast", true);
        display.addProperty("announce_to_chat", true);
        display.addProperty("hidden", false);

        JsonObject trigger = new JsonObject();
        trigger.addProperty("trigger", "minecraft:impossible");
        JsonObject criteria = new JsonObject();
        criteria.add(CRITERION, trigger);

        JsonObject root = new JsonObject();
        root.addProperty("parent", "minecraft:adventure/root");
        root.add("display", display);
        root.add("criteria", criteria);
        try {
            advancement = Bukkit.getUnsafe().loadAdvancement(key, root.toString(), false);
            if (advancement == null) {
                plugin.getLogger().warning("Ачивка «Вавилонская башня» не загрузилась.");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Не удалось зарегистрировать ачивку «Вавилонская башня»: " + e.getMessage());
        }
    }

    /** Вызывается из голосового потока на каждый пакет микрофона. */
    public void heard(UUID speaker) {
        long now = System.currentTimeMillis();
        lastSpoke.put(speaker, now);
        Long checked = lastCheck.get(speaker);
        if (checked != null && now - checked < CHECK_EVERY_MS) {
            return;
        }
        lastCheck.put(speaker, now);
        Bukkit.getScheduler().runTask(plugin, () -> check(speaker, now));
    }

    private void check(UUID speakerId, long now) {
        if (advancement == null || !plugin.flag("babel.enabled") || tooEarly(now)) {
            return;
        }
        Player speaker = Bukkit.getPlayer(speakerId);
        if (!eligible(speaker)) {
            return;
        }
        double radius = Math.max(1, plugin.getConfig().getDouble("babel.radius", 10));
        long window = Math.max(1, plugin.getConfig().getInt("babel.window-seconds", 5)) * 1000L;
        String language = language(speaker);
        for (Player other : speaker.getWorld().getPlayers()) {
            if (other == speaker || !eligible(other) || language.equals(language(other))) {
                continue;
            }
            Long otherSpoke = lastSpoke.get(other.getUniqueId());
            if (otherSpoke == null || now - otherSpoke > window) {
                continue;
            }
            if (other.getLocation().distanceSquared(speaker.getLocation()) > radius * radius) {
                continue;
            }
            award(speaker);
            award(other);
        }
    }

    /** В лобби и первые N минут после старта SMP ачивку не выдаём. */
    private boolean tooEarly(long now) {
        Phase phase = plugin.start().phase();
        if (phase == Phase.LOBBY) {
            return true;
        }
        long delay = Math.max(0, plugin.getConfig().getInt("babel.after-start-minutes", 30)) * 60_000L;
        return phase == Phase.RUNNING && now - plugin.state().startedAt() < delay;
    }

    private boolean eligible(Player player) {
        return player != null && player.isOnline()
                && player.getGameMode() != GameMode.SPECTATOR
                && !plugin.vanish().isVanished(player);
    }

    private static String language(Player player) {
        return player.locale().getLanguage().toLowerCase(Locale.ROOT);
    }

    private void award(Player player) {
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (!progress.isDone()) {
            progress.awardCriteria(CRITERION);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSpoke.remove(event.getPlayer().getUniqueId());
        lastCheck.remove(event.getPlayer().getUniqueId());
    }
}
