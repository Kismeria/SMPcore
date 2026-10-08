package me.kismeria.smpcore.feature;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Упоминания @ник: ник подсвечивается в чате, упомянутому — звук, уведомление как у достижения
 * (с головой отправителя) и строка над хотбаром. В чате @ + Tab дописывает ник.
 */
public final class Mentions implements Listener {

    private static final Pattern MENTION = Pattern.compile("@(\\.?[A-Za-z0-9_]{1,16})");
    private static final String ALL = "here";
    private static final String CRITERION = "mention";

    private final SmpCore plugin;
    /** Ачивка-уведомление на каждого отправителя: регистрация рассылает данные ачивок всем, поэтому один раз на игрока. */
    private final Map<UUID, Advancement> toasts = new HashMap<>();
    private final Map<String, Long> lastPing = new ConcurrentHashMap<>();

    public Mentions(SmpCore plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- автодополнение

    public void refreshCompletions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setCustomChatCompletions(completionsFor(player));
        }
    }

    private List<String> completionsFor(Player viewer) {
        List<String> list = new ArrayList<>();
        if (!plugin.flag("mentions.enabled")) {
            return list;
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != viewer && !plugin.vanish().isVanished(other)) {
                list.add("@" + other.getName());
            }
        }
        if (viewer.hasPermission("smpcore.mention.all")) {
            list.add("@" + ALL);
        }
        return list;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // ваниш применяется на HIGHEST — дополнения пересчитываем после
        Bukkit.getScheduler().runTask(plugin, this::refreshCompletions);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Bukkit.getScheduler().runTask(plugin, this::refreshCompletions);
    }

    // ---------------------------------------------------------------- чат

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.flag("mentions.enabled")) {
            return;
        }
        Player sender = event.getPlayer();
        Map<String, Player> online = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plugin.vanish().isVanished(player)) {
                online.put(player.getName().toLowerCase(Locale.ROOT), player);
            }
        }
        boolean canAll = sender.hasPermission("smpcore.mention.all");
        Set<Player> mentioned = new LinkedHashSet<>();
        String format = plugin.getConfig().getString("mentions.format", "<yellow><bold>@<player>");

        TextReplacementConfig replacement = TextReplacementConfig.builder().match(MENTION).replacement((match, original) -> {
            String name = match.group(1);
            if (canAll && name.equalsIgnoreCase(ALL)) {
                mentioned.addAll(online.values());
                return Text.mm(format, Lang.txt("player", ALL));
            }
            Player target = online.get(name.toLowerCase(Locale.ROOT));
            if (target == null) {
                return original;
            }
            mentioned.add(target);
            return Text.mm(format, Lang.txt("player", target.getName()))
                    .hoverEvent(HoverEvent.showText(Component.text(target.getName())))
                    .clickEvent(ClickEvent.suggestCommand("@" + target.getName() + " "));
        }).build();
        event.message(event.message().replaceText(replacement));

        // себя не пингуем — кроме админов (чтобы проверить звук и уведомление в одиночку)
        if (!sender.hasPermission("smpcore.mention.self")) {
            mentioned.remove(sender);
        }
        if (mentioned.isEmpty()) {
            return;
        }
        String preview = Text.plain(event.message());
        UUID senderId = sender.getUniqueId();
        List<UUID> targets = mentioned.stream().map(Player::getUniqueId).toList();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player from = Bukkit.getPlayer(senderId);
            if (from == null) {
                return;
            }
            for (UUID id : targets) {
                Player target = Bukkit.getPlayer(id);
                if (target != null) {
                    notify(from, target, preview);
                }
            }
        });
    }

    private void notify(Player from, Player target, String message) {
        if (target.hasPermission("smpcore.mention.bypass")) {
            return;
        }
        long now = System.currentTimeMillis();
        String pair = from.getUniqueId() + ">" + target.getUniqueId();
        Long last = lastPing.get(pair);
        long cooldown = Math.max(0, plugin.getConfig().getInt("mentions.cooldown-seconds", 3)) * 1000L;
        if (last != null && now - last < cooldown) {
            return;
        }
        lastPing.put(pair, now);

        if (plugin.flag("sounds.mention.enabled")) {
            String sound = plugin.getConfig().getString("sounds.mention.sound", "minecraft:block.note_block.pling");
            target.playSound(target, sound, SoundCategory.MASTER,
                    (float) plugin.getConfig().getDouble("sounds.mention.volume", 1.0),
                    (float) plugin.getConfig().getDouble("sounds.mention.pitch", 1.6));
        }
        plugin.lang().actionBar(target, "mention.actionbar", Lang.txt("player", from.getName()));
        if (plugin.flag("mentions.toast")) {
            toast(from, target);
        }
    }

    // ---------------------------------------------------------------- уведомление-ачивка

    private void toast(Player from, Player target) {
        Advancement advancement = toasts.computeIfAbsent(from.getUniqueId(), id -> register(from));
        if (advancement == null) {
            return;
        }
        AdvancementProgress progress = target.getAdvancementProgress(advancement);
        progress.revokeCriteria(CRITERION);
        progress.awardCriteria(CRITERION);
        // сразу забираем обратно — ачивка не висит в меню и сработает снова
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (target.isOnline()) {
                target.getAdvancementProgress(advancement).revokeCriteria(CRITERION);
            }
        }, 2L);
    }

    private Advancement register(Player from) {
        NamespacedKey key = new NamespacedKey(plugin, "mention/" + from.getUniqueId().toString().replace("-", ""));
        Advancement existing = Bukkit.getAdvancement(key);
        if (existing != null) {
            return existing;
        }
        String fallbackLocale = plugin.lang().fallback();
        JsonObject title = new JsonObject();
        title.addProperty("translate", Lang.CLIENT_PREFIX + "mention.toast");
        JsonArray with = new JsonArray();
        with.add(from.getName());
        title.add("with", with);
        title.addProperty("fallback", plugin.lang().raw(fallbackLocale, "mention.toast").replace("{0}", from.getName()));
        JsonObject description = new JsonObject();
        description.addProperty("translate", Lang.CLIENT_PREFIX + "mention.description");
        description.addProperty("fallback", plugin.lang().raw(fallbackLocale, "mention.description"));

        // иконка — голова отправителя (со скином, если он есть в профиле)
        JsonObject profile = new JsonObject();
        profile.addProperty("name", from.getName());
        JsonArray properties = new JsonArray();
        for (ProfileProperty property : from.getPlayerProfile().getProperties()) {
            if (property.getName().equals("textures")) {
                JsonObject textures = new JsonObject();
                textures.addProperty("name", "textures");
                textures.addProperty("value", property.getValue());
                if (property.getSignature() != null) {
                    textures.addProperty("signature", property.getSignature());
                }
                properties.add(textures);
            }
        }
        if (!properties.isEmpty()) {
            profile.add("properties", properties);
        }
        JsonObject components = new JsonObject();
        components.add("minecraft:profile", profile);
        JsonObject icon = new JsonObject();
        icon.addProperty("id", "minecraft:player_head");
        icon.add("components", components);

        JsonObject display = new JsonObject();
        display.add("icon", icon);
        display.add("title", title);
        display.add("description", description);
        display.addProperty("frame", "goal");
        display.addProperty("show_toast", true);
        display.addProperty("announce_to_chat", false);
        display.addProperty("hidden", true);

        JsonObject trigger = new JsonObject();
        trigger.addProperty("trigger", "minecraft:impossible");
        JsonObject criteria = new JsonObject();
        criteria.add(CRITERION, trigger);

        JsonObject root = new JsonObject();
        root.addProperty("parent", "minecraft:adventure/root");
        root.add("display", display);
        root.add("criteria", criteria);
        try {
            return Bukkit.getUnsafe().loadAdvancement(key, root.toString(), false);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Не удалось создать уведомление упоминания: " + e.getMessage());
            return null;
        }
    }
}
