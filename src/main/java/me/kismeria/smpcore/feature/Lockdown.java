package me.kismeria.smpcore.feature;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /lockdown — техработы: зайти могут только операторы, остальных кикает. В списке серверов
 * вместо онлайна — «Техработы» и своё описание. Иконки техработ — plugins/SmpCore/icons/lockdown/ (см. MotdManager).
 */
public final class Lockdown implements TabExecutor, Listener {

    private final SmpCore plugin;

    public Lockdown(SmpCore plugin) {
        this.plugin = plugin;
    }

    public boolean active() {
        return plugin.flag("lockdown.enabled");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean enable = args.length == 0 ? !active() : switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "вкл" -> true;
            case "off", "выкл" -> false;
            default -> !active();
        };
        if (enable == active()) {
            plugin.lang().send(sender, enable ? "lockdown.already-on" : "lockdown.already-off");
            return true;
        }
        plugin.set("lockdown.enabled", enable);
        plugin.motd().loadIcons();
        if (enable) {
            for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                if (!player.isOp()) {
                    player.kick(plugin.lang().get(player, "lockdown.kick"));
                }
            }
        }
        plugin.lang().broadcast(enable ? "lockdown.on" : "lockdown.off");
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player, enable ? Sound.BLOCK_ANVIL_LAND : Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, enable ? 0.8f : 1.4f);
        }
        return true;
    }

    /** Оператор? Проверяем и по UUID, и по нику: в офлайн-режиме UUID у лицензии и пиратки разные. */
    private static boolean operator(UUID id, String name) {
        for (OfflinePlayer op : Bukkit.getOperators()) {
            if (op.getUniqueId().equals(id) || op.getName() != null && op.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !active()
                || operator(event.getUniqueId(), event.getName())) {
            return;
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                plugin.lang().get(Bukkit.getConsoleSender(), "lockdown.kick"));
    }

    /** После MotdManager (HIGH): техработы важнее обычного MOTD. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(PaperServerListPingEvent event) {
        if (!active()) {
            return;
        }
        event.motd(Text.mm(plugin.getConfig().getString("lockdown.motd",
                "<gold><bold>Технические работы</bold></gold><newline><gray>Скоро вернёмся!")));
        event.setVersion(Text.legacy(plugin.getConfig().getString("lockdown.version-text", "<red>Техработы")));
        event.setProtocolVersion(-1);
        event.getListedPlayers().clear();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("on", "off") : List.of();
    }
}
