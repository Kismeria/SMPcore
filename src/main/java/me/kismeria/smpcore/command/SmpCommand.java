package me.kismeria.smpcore.command;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.StateStore.Phase;
import me.kismeria.smpcore.feature.DimensionControl.Dim;
import me.kismeria.smpcore.gui.menu.MainMenu;
import me.kismeria.smpcore.util.Durations;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class SmpCommand implements TabExecutor {

    private static final List<String> ADMIN = List.of("menu", "start", "cancel", "lobby", "reset", "reload", "skin", "info");

    private final SmpCore plugin;

    public SmpCommand(SmpCore plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("info") || !sender.hasPermission("smpcore.admin")) {
            info(sender);
            return true;
        }
        switch (sub) {
            case "menu" -> {
                if (sender instanceof Player player) {
                    new MainMenu(plugin, player).open();
                } else {
                    lang().send(sender, "command.menu-only-in-game", Lang.txt("commands", String.join(", ", ADMIN)));
                }
            }
            case "start" -> start(sender, Arrays.copyOfRange(args, 1, args.length));
            case "cancel" -> plugin.start().cancelSchedule();
            case "lobby" -> plugin.start().prepareLobby();
            case "reset" -> {
                plugin.start().reset();
                lang().send(sender, "command.reset-done");
            }
            case "skin" -> {
                if (args.length < 2) {
                    lang().send(sender, "skin.usage");
                } else if (args[1].equalsIgnoreCase("all")) {
                    plugin.skins().refresh(Bukkit.getOnlinePlayers(), sender);
                } else {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        lang().send(sender, "skin.not-online", Lang.txt("player", args[1]));
                    } else {
                        plugin.skins().refresh(List.of(target), sender);
                    }
                }
            }
            case "reload" -> {
                plugin.reload();
                lang().send(sender, "command.reloaded");
            }
            default -> lang().send(sender, "command.subcommands", Lang.txt("commands", String.join(", ", ADMIN)));
        }
        return true;
    }

    private void start(CommandSender sender, String[] args) {
        if (args.length == 0) {
            lang().send(sender, "command.start-usage");
            return;
        }
        String input = String.join(" ", args);
        if (input.equalsIgnoreCase("now") || input.equalsIgnoreCase("сейчас")) {
            if (plugin.start().phase() == Phase.RUNNING) {
                lang().send(sender, "command.already-running");
            } else {
                plugin.start().startNow();
            }
            return;
        }
        long at = Durations.parseMoment(input, plugin.zone(), System.currentTimeMillis());
        if (at <= System.currentTimeMillis()) {
            lang().send(sender, "command.unknown-time", Lang.txt("input", input));
            return;
        }
        if (!plugin.start().schedule(at)) {
            lang().send(sender, "command.already-running");
        }
    }

    private void info(CommandSender sender) {
        long now = System.currentTimeMillis();
        switch (plugin.start().phase()) {
            case IDLE -> lang().send(sender, "info.idle");
            case LOBBY -> {
                long at = plugin.state().scheduledAt();
                if (at > 0) {
                    lang().send(sender, "info.lobby-time", TagResolver.resolver(
                            Lang.ph("time", Durations.clock(at, plugin.zone())),
                            Lang.ph("left", lang().duration(sender, (at - now) / 1000))));
                } else {
                    lang().send(sender, "info.lobby-wait");
                }
            }
            case RUNNING -> {
                lang().send(sender, "info.running", Lang.ph("left", lang().duration(sender, (now - plugin.state().startedAt()) / 1000)));
                if (plugin.start().growthEnabled()) {
                    lang().send(sender, "info.next-growth", Lang.ph("left", lang().duration(sender, (plugin.start().nextGrowthAt() - now) / 1000)));
                }
            }
        }
        if (plugin.start().phase() != Phase.IDLE) {
            if (plugin.mining().enabled()) {
                lang().send(sender, "info.deepslate", Lang.ph("status", plugin.mining().status(sender)));
            }
            if (plugin.mining().netheriteEnabled()) {
                lang().send(sender, "info.netherite", Lang.ph("status", plugin.mining().netheriteStatus(sender)));
            }
        }
        lang().send(sender, "info.dimensions",
                Lang.ph("nether", plugin.dimensions().status(sender, Dim.NETHER)),
                Lang.ph("end", plugin.dimensions().status(sender, Dim.END)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("smpcore.admin")) {
            return args.length == 1 ? List.of("info") : List.of();
        }
        if (args.length == 1) {
            return ADMIN.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("skin")) {
            List<String> names = new java.util.ArrayList<>(List.of("all"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return names.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return List.of("now", "18:00", "30m", "1h");
        }
        return List.of();
    }
}
