package me.kismeria.smpcore.command;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.SafeSpot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /back — вернуться туда, откуда телепортировался, или на место смерти (для админов). */
public final class BackCommand implements TabExecutor, Listener {

    private final SmpCore plugin;
    private final Map<UUID, Location> back = new HashMap<>();

    public BackCommand(SmpCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // только телепорты командами (/tp, /spawn, /back, спектатор, плагины). Жемчуг, порталы,
        // хорус и кровать не перетирают точку — иначе /back уводит в портал, а не туда, откуда тпшнулся
        boolean command = switch (event.getCause()) {
            case COMMAND, PLUGIN, SPECTATE, UNKNOWN -> true;
            default -> false;
        };
        boolean far = event.getFrom().getWorld() != event.getTo().getWorld()
                || event.getFrom().distanceSquared(event.getTo()) > 5 * 5;
        if (command && far && event.getPlayer().hasPermission("smpcore.back")) {
            back.put(event.getPlayer().getUniqueId(), event.getFrom().clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getPlayer().hasPermission("smpcore.back")) {
            back.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation().clone());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "invsee.only-players");
            return true;
        }
        Location target = back.get(player.getUniqueId());
        if (target == null || target.getWorld() == null) {
            plugin.lang().send(player, "back.none");
            return true;
        }
        // сам телепорт запомнит текущее место — повторный /back вернёт обратно
        player.teleportAsync(safe(target), TeleportCause.COMMAND).thenAccept(done -> {
            if (done) {
                player.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
                plugin.lang().send(player, "back.done");
            }
        });
        return true;
    }

    /** Умер в лаве или в бездне — ставим на ближайшую твёрдую землю в той же колонне. */
    private static Location safe(Location target) {
        World world = target.getWorld();
        int x = target.getBlockX();
        int z = target.getBlockZ();
        if (target.getY() >= world.getMinHeight() && SafeSpot.canStand(world, x, target.getBlockY(), z)) {
            return target;
        }
        int ceiling = world.getEnvironment() == World.Environment.NETHER && target.getY() < 128 ? 128 : world.getMaxHeight();
        Location spot = SafeSpot.stand(world, x, z, Math.max(world.getMinHeight(), target.getBlockY()), ceiling);
        spot.setYaw(target.getYaw());
        spot.setPitch(target.getPitch());
        return spot;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
