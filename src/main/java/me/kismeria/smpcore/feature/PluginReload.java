package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;

/**
 * /plugins reload [плагин] — перезагрузить плагин без рестарта (по умолчанию SmpCore).
 * Сама перезагрузка — PlugManX: команду просто переписываем в /plugman reload, чтобы в момент
 * выгрузки на стеке не было кода SmpCore. Новый jar кладётся поверх старого в plugins/.
 */
public final class PluginReload implements Listener {

    private final SmpCore plugin;

    public PluginReload(SmpCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayer(PlayerCommandPreprocessEvent event) {
        String rewritten = rewrite(event.getPlayer(), event.getMessage().substring(1));
        if (rewritten == null) {
            return;
        }
        if (rewritten.isEmpty()) {
            event.setCancelled(true);
        } else {
            event.setMessage("/" + rewritten);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsole(ServerCommandEvent event) {
        String rewritten = rewrite(event.getSender(), event.getCommand());
        if (rewritten == null) {
            return;
        }
        if (rewritten.isEmpty()) {
            event.setCancelled(true);
        } else {
            event.setCommand(rewritten);
        }
    }

    /** @return null — не наша команда, "" — отменить, иначе новая команда. */
    private String rewrite(CommandSender sender, String line) {
        String[] parts = line.trim().split("\s+");
        if (parts.length < 2 || !parts[1].equalsIgnoreCase("reload")) {
            return null;
        }
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (label.startsWith("bukkit:")) {
            label = label.substring("bukkit:".length());
        }
        if (!label.equals("plugins") && !label.equals("pl") || !sender.hasPermission("smpcore.admin")) {
            return null;
        }
        if (Bukkit.getPluginManager().getPlugin("PlugManX") == null) {
            plugin.lang().send(sender, "reload.no-plugman");
            return "";
        }
        String target = parts.length >= 3 ? parts[2] : plugin.getName();
        return "plugman reload " + target;
    }
}
