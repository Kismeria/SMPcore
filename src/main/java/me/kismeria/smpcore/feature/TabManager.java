package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.PlatformIcon;
import me.kismeria.smpcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Шапка/подвал таба на языке игрока и формат ника в табе. */
public final class TabManager implements Listener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final SmpCore plugin;
    private BukkitTask task;
    private boolean applied;

    public TabManager(SmpCore plugin) {
        this.plugin = plugin;
    }

    public void restart() {
        if (task != null) {
            task.cancel();
        }
        long period = Math.max(1, plugin.getConfig().getInt("tab.update-seconds", 2)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, period);
    }

    private void updateAll() {
        if (!plugin.flag("tab.enabled")) {
            if (applied) {
                applied = false;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
                    player.playerListName(null);
                }
            }
            return;
        }
        applied = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player);
        }
    }

    private void update(Player player) {
        String locale = plugin.lang().localeOf(player);
        TagResolver resolver = TagResolver.resolver(
                Lang.ph("online", Bukkit.getOnlinePlayers().stream().filter(player::canSee).count()),
                Lang.ph("max", Bukkit.getMaxPlayers()),
                Lang.ph("tps", String.format(Locale.ROOT, "%.1f", Math.min(20.0, Bukkit.getTPS()[0]))),
                Lang.ph("ping", player.getPing()),
                Lang.txt("player", player.getName()),
                Lang.txt("world", player.getWorld().getName()),
                Lang.ph("time", TIME.format(ZonedDateTime.now(plugin.zone()))),
                Lang.ph("status", plugin.start().statusText(locale)));
        String header = String.join("<newline>", plugin.lang().rawList(locale, "tab.header"));
        String footer = String.join("<newline>", plugin.lang().rawList(locale, "tab.footer"));
        player.sendPlayerListHeaderAndFooter(Text.mm(header, resolver), Text.mm(footer, resolver));

        // имя в табе одно на всех — иконка спрайтом (её видят Java-клиенты)
        String nameFormat = plugin.getConfig().getString("tab.name-format", "");
        Component icon = PlatformIcon.of(plugin, player, false);
        if (nameFormat != null && !nameFormat.isBlank()) {
            player.playerListName(icon.append(Text.mm(nameFormat, Lang.txt("player", player.getName()), Lang.ph("ping", player.getPing()))));
        } else if (plugin.flag("platform-icons.enabled")) {
            player.playerListName(icon.append(Component.text(player.getName())));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.flag("tab.enabled")) {
            Bukkit.getScheduler().runTask(plugin, () -> update(event.getPlayer()));
        }
    }
}
