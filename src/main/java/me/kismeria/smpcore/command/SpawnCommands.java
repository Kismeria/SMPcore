package me.kismeria.smpcore.command;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.SafeSpot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;
import java.util.Locale;

/** /spawn и /nether [ник] — телепорт на спавн обычного мира или ада (для админов). */
public final class SpawnCommands implements TabExecutor {

    private final SmpCore plugin;

    public SpawnCommands(SmpCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target;
        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                plugin.lang().send(sender, "punish.not-found", Lang.txt("player", args[0]));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            plugin.lang().send(sender, "invsee.only-players");
            return true;
        }

        boolean nether = command.getName().equalsIgnoreCase("nether");
        World world = nether ? plugin.worldOf(World.Environment.NETHER) : plugin.mainWorld();
        if (world == null) {
            plugin.lang().send(sender, "teleport.no-nether");
            return true;
        }
        Location spawn = world.getSpawnLocation();
        // в аду сверху бедроковая крыша — ищем место под ней
        int ceiling = nether ? 127 : world.getMaxHeight();
        int prefer = nether ? Math.min(spawn.getBlockY(), 100) : spawn.getBlockY();
        Location safe = SafeSpot.stand(world, spawn.getBlockX(), spawn.getBlockZ(), prefer, ceiling);
        safe.setYaw(spawn.getYaw());
        target.teleportAsync(safe, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(done -> {
            if (done) {
                target.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
            }
        });
        String key = nether ? "teleport.nether" : "teleport.spawn";
        plugin.lang().send(target, key);
        if (target != sender) {
            plugin.lang().send(sender, key + "-other", Lang.txt("player", target.getName()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
