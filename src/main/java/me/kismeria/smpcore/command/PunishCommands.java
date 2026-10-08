package me.kismeria.smpcore.command;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.feature.PunishmentManager;
import me.kismeria.smpcore.feature.PunishmentManager.Type;
import me.kismeria.smpcore.util.Durations;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** /ban /unban /mute /unmute /kick /history */
public final class PunishCommands implements TabExecutor {

    private static final Set<String> FOREVER = Set.of("perm", "permanent", "forever", "навсегда", "-");

    private final SmpCore plugin;

    public PunishCommands(SmpCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 0) {
            plugin.lang().send(sender, "punish.usage-" + (name.equals("ban") || name.equals("mute") ? "timed" : "simple"),
                    Lang.txt("cmd", name));
            return true;
        }
        OfflinePlayer target = PunishmentManager.resolve(args[0]);
        if (target == null) {
            plugin.lang().send(sender, "punish.not-found", Lang.txt("player", args[0]));
            return true;
        }
        String targetName = target.getName() != null ? target.getName() : args[0];
        PunishmentManager punishments = plugin.punishments();
        switch (name) {
            case "ban", "mute" -> {
                if (target.getPlayer() != null && target.getPlayer().hasPermission("smpcore.punish.exempt") && sender != target.getPlayer()) {
                    plugin.lang().send(sender, "punish.exempt");
                    return true;
                }
                long seconds = 0;
                int reasonFrom = 1;
                if (args.length > 1) {
                    if (FOREVER.contains(args[1].toLowerCase(Locale.ROOT))) {
                        reasonFrom = 2;
                    } else {
                        long parsed = Durations.parseDurationSeconds(args[1]);
                        if (parsed > 0) {
                            seconds = parsed;
                            reasonFrom = 2;
                        }
                    }
                }
                String reason = String.join(" ", Arrays.copyOfRange(args, Math.min(reasonFrom, args.length), args.length));
                punishments.punish(target, name.equals("ban") ? Type.BAN : Type.MUTE, seconds, reason, sender);
            }
            case "unban", "unmute" -> {
                Type type = name.equals("unban") ? Type.BAN : Type.MUTE;
                if (!punishments.pardon(target, type, sender)) {
                    plugin.lang().send(sender, type == Type.BAN ? "punish.not-banned" : "punish.not-muted", Lang.txt("player", targetName));
                }
            }
            case "kick" -> {
                Player online = target.getPlayer();
                if (online == null) {
                    plugin.lang().send(sender, "punish.not-found", Lang.txt("player", targetName));
                    return true;
                }
                if (online.hasPermission("smpcore.punish.exempt") && sender != online) {
                    plugin.lang().send(sender, "punish.exempt");
                    return true;
                }
                punishments.kick(online, String.join(" ", Arrays.copyOfRange(args, 1, args.length)), sender);
            }
            case "history" -> {
                List<String> entries = punishments.history(target.getUniqueId());
                plugin.lang().send(sender, "punish.history-header", Lang.txt("player", targetName));
                if (entries.isEmpty()) {
                    plugin.lang().send(sender, "punish.history-empty");
                }
                int from = Math.max(0, entries.size() - 15);
                for (String entry : entries.subList(from, entries.size())) {
                    sender.sendMessage(plugin.lang().get(sender, "punish.history-line", Lang.txt("entry", entry)));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 2 && (name.equals("ban") || name.equals("mute"))) {
            return List.of("15m", "1h", "1d", "7d", "30d", "perm");
        }
        return List.of();
    }
}
