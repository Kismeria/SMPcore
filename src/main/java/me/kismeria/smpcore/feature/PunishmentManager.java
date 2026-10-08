package me.kismeria.smpcore.feature;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Durations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Баны, муты, кики. Мут глушит и чат, и личку, и Simple Voice Chat (см. VoiceBridge). */
public final class PunishmentManager implements Listener {

    public enum Type {
        BAN, MUTE;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** @param until 0 — навсегда */
    public record Punishment(Type type, long until, String reason, String by, long at) {
        public boolean active(long now) {
            return until == 0 || now < until;
        }

        public long secondsLeft(long now) {
            return until == 0 ? -1 : Math.max(0, (until - now) / 1000);
        }
    }

    private final SmpCore plugin;
    private final File file;
    private final Map<UUID, Map<Type, Punishment>> active = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> history = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public PunishmentManager(SmpCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "punishments.yml");
    }

    // ---------------------------------------------------------------- хранение

    public void load() {
        active.clear();
        history.clear();
        names.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            names.put(id, section.getString("name", key));
            history.put(id, new ArrayList<>(section.getStringList("history")));
            for (Type type : Type.values()) {
                ConfigurationSection p = section.getConfigurationSection(type.key());
                if (p != null) {
                    active.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(type, new Punishment(type,
                            p.getLong("until"), p.getString("reason", ""), p.getString("by", "?"), p.getLong("at")));
                }
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Наказания SmpCore. until: 0 — навсегда."));
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            String base = "players." + entry.getKey();
            yaml.set(base + ".name", entry.getValue());
            yaml.set(base + ".history", history.getOrDefault(entry.getKey(), List.of()));
            Map<Type, Punishment> map = active.get(entry.getKey());
            if (map == null) {
                continue;
            }
            for (Punishment p : map.values()) {
                if (!p.active(now)) {
                    continue;
                }
                String path = base + "." + p.type().key();
                yaml.set(path + ".until", p.until());
                yaml.set(path + ".reason", p.reason());
                yaml.set(path + ".by", p.by());
                yaml.set(path + ".at", p.at());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось сохранить punishments.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- API

    public Punishment get(UUID id, Type type) {
        Map<Type, Punishment> map = active.get(id);
        if (map == null) {
            return null;
        }
        Punishment p = map.get(type);
        if (p != null && !p.active(System.currentTimeMillis())) {
            map.remove(type);
            return null;
        }
        return p;
    }

    /** Потокобезопасно, вызывается и из голосового потока. */
    public boolean isMuted(UUID id) {
        return get(id, Type.MUTE) != null;
    }

    public List<String> history(UUID id) {
        return history.getOrDefault(id, List.of());
    }

    public String knownName(UUID id) {
        return names.get(id);
    }

    private static String byName(CommandSender by) {
        return by instanceof Player player ? player.getName() : "Console";
    }

    private void record(UUID id, String name, String entry) {
        names.put(id, name);
        String stamp = Durations.clock(System.currentTimeMillis(), plugin.zone());
        history.computeIfAbsent(id, k -> new ArrayList<>()).add(stamp + " | " + entry);
    }

    public void punish(OfflinePlayer target, Type type, long seconds, String reason, CommandSender by) {
        long now = System.currentTimeMillis();
        long until = seconds > 0 ? now + seconds * 1000L : 0;
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        Punishment p = new Punishment(type, until, reason, byName(by), now);
        active.computeIfAbsent(target.getUniqueId(), k -> new ConcurrentHashMap<>()).put(type, p);
        record(target.getUniqueId(), name, type + " " + (seconds > 0 ? Durations.human(seconds) : "∞") + " | " + p.by() + " | " + reason);
        save();

        Player online = target.getPlayer();
        if (type == Type.BAN && online != null) {
            online.kick(banScreen(online, p));
        } else if (type == Type.MUTE && online != null) {
            plugin.lang().send(online, "punish.you-muted", resolvers(online, name, p));
        }
        announce(type == Type.BAN ? "punish.broadcast-ban" : "punish.broadcast-mute", name, p, by, type == Type.BAN);
    }

    public boolean pardon(OfflinePlayer target, Type type, CommandSender by) {
        Map<Type, Punishment> map = active.get(target.getUniqueId());
        if (map == null || map.remove(type) == null) {
            return false;
        }
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        record(target.getUniqueId(), name, "UN" + type + " | " + byName(by));
        save();
        Player online = target.getPlayer();
        if (type == Type.MUTE && online != null) {
            plugin.lang().send(online, "punish.you-unmuted");
        }
        String key = type == Type.BAN ? "punish.unbanned" : "punish.unmuted";
        if (type == Type.BAN && plugin.flag("punish.broadcast")) {
            plugin.lang().broadcast(key, Lang.txt("player", name), Lang.txt("by", byName(by)));
        } else {
            plugin.lang().send(by, key, Lang.txt("player", name), Lang.txt("by", byName(by)));
        }
        return true;
    }

    public void kick(Player target, String reason, CommandSender by) {
        Punishment p = new Punishment(Type.BAN, 0, reason, byName(by), System.currentTimeMillis());
        record(target.getUniqueId(), target.getName(), "KICK | " + p.by() + " | " + reason);
        save();
        target.kick(plugin.lang().get(target, "punish.kick-screen",
                Lang.txt("reason", reasonOrDefault(target, reason)), Lang.txt("by", p.by())));
        announce("punish.broadcast-kick", target.getName(), p, by, false);
    }

    /** Всем в чат — только баны; муты и кики видит лишь тот, кто выдал. */
    private void announce(String key, String name, Punishment p, CommandSender by, boolean global) {
        if (global && plugin.flag("punish.broadcast")) {
            plugin.lang().broadcast(key, viewer -> resolvers(viewer, name, p));
        } else {
            plugin.lang().send(by, key, resolvers(by, name, p));
        }
    }

    private String reasonOrDefault(CommandSender viewer, String reason) {
        return reason == null || reason.isBlank() ? plugin.lang().raw(viewer, "punish.no-reason") : reason;
    }

    private TagResolver resolvers(CommandSender viewer, String name, Punishment p) {
        long left = p.secondsLeft(System.currentTimeMillis());
        return TagResolver.resolver(
                Lang.txt("player", name),
                Lang.txt("by", p.by()),
                Lang.txt("reason", reasonOrDefault(viewer, p.reason())),
                Lang.ph("left", left < 0 ? plugin.lang().raw(viewer, "punish.forever") : plugin.lang().duration(viewer, left)));
    }

    private Component banScreen(CommandSender viewer, Punishment p) {
        return plugin.lang().get(viewer, "punish.ban-screen", resolvers(viewer, "", p));
    }

    // ---------------------------------------------------------------- применение

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        Punishment ban = get(event.getUniqueId(), Type.BAN);
        if (ban != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(Bukkit.getConsoleSender(), ban));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Punishment mute = get(player.getUniqueId(), Type.MUTE);
        if (mute != null) {
            event.setCancelled(true);
            plugin.lang().send(player, "punish.muted", resolvers(player, player.getName(), mute));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        Punishment mute = get(player.getUniqueId(), Type.MUTE);
        if (mute == null) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) {
            label = label.substring(label.indexOf(':') + 1);
        }
        if (plugin.getConfig().getStringList("punish.muted-commands").contains(label)) {
            event.setCancelled(true);
            plugin.lang().send(player, "punish.muted", resolvers(player, player.getName(), mute));
        }
    }

    /** Вызывается из голосового потока, когда замученный пытается говорить. */
    void notifyVoiceMuted(UUID id) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(id);
        if (last != null && now - last < 3000) {
            return;
        }
        lastNotice.put(id, now);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                plugin.lang().actionBar(player, "punish.muted-voice");
            }
        });
    }

    /** Ник → игрок: сначала онлайн, потом кэш сервера (без запросов в Mojang). */
    public static OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }
}
