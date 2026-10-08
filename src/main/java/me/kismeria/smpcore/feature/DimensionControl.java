package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.StateStore.Phase;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;

import java.time.Duration;

/** Ад и энд: ручное включение и открытие по таймеру после старта. */
public final class DimensionControl implements Listener {

    /** За сколько минут до открытия предупреждать. */
    private static final int[] WARN_MINUTES = {10, 1};

    private final SmpCore plugin;
    private int netherWarned = Integer.MAX_VALUE;
    private int endWarned = Integer.MAX_VALUE;

    public DimensionControl(SmpCore plugin) {
        this.plugin = plugin;
    }

    public enum Dim {
        NETHER("nether"), END("end");

        final String key;

        Dim(String key) {
            this.key = key;
        }
    }

    // ---------------------------------------------------------------- состояние

    public boolean enabledFlag(Dim dim) {
        return plugin.flag("world." + dim.key + "-enabled");
    }

    public int openAfterMinutes(Dim dim) {
        return Math.max(0, plugin.getConfig().getInt("world." + dim.key + "-open-after-minutes", 0));
    }

    /** Когда откроется по таймеру, или 0, если таймер не действует. */
    public long unlockAt(Dim dim) {
        if (plugin.state().phase() != Phase.RUNNING || openAfterMinutes(dim) == 0) {
            return 0;
        }
        return plugin.state().startedAt() + openAfterMinutes(dim) * 60_000L;
    }

    public boolean open(Dim dim) {
        return enabledFlag(dim) && System.currentTimeMillis() >= unlockAt(dim);
    }

    public boolean netherEnabled() {
        return open(Dim.NETHER);
    }

    public boolean endEnabled() {
        return open(Dim.END);
    }

    public void setEnabled(Dim dim, boolean enabled) {
        plugin.set("world." + dim.key + "-enabled", enabled);
        if (enabled && System.currentTimeMillis() >= unlockAt(dim)) {
            announceOpened(dim);
        } else if (!enabled) {
            plugin.lang().broadcast("dimension." + dim.key + "-closed");
        }
        evacuateAll();
    }

    /** Строка статуса для /smp info на языке получателя. */
    public String status(CommandSender viewer, Dim dim) {
        if (!enabledFlag(dim)) {
            return plugin.lang().raw(viewer, "info.closed");
        }
        long left = (unlockAt(dim) - System.currentTimeMillis()) / 1000;
        if (left > 0) {
            return plugin.lang().raw(viewer, "info.opens-in").replace("<left>", plugin.lang().duration(viewer, left));
        }
        return plugin.lang().raw(viewer, "info.open");
    }

    private static Dim of(World.Environment environment) {
        return switch (environment) {
            case NETHER -> Dim.NETHER;
            case THE_END -> Dim.END;
            default -> null;
        };
    }

    private boolean allowed(World.Environment environment) {
        Dim dim = of(environment);
        return dim == null || open(dim);
    }

    private boolean lobbyBlocksPortals() {
        return plugin.state().phase() == Phase.LOBBY && plugin.flag("lobby.no-portals");
    }

    private void denyBar(Player player, Dim dim) {
        long left = (unlockAt(dim) - System.currentTimeMillis()) / 1000;
        if (enabledFlag(dim) && left > 0) {
            plugin.lang().actionBar(player, "dimension." + dim.key + "-locked-bar",
                    Lang.ph("left", plugin.lang().duration(player, left)));
        } else {
            plugin.lang().actionBar(player, "dimension." + dim.key + "-disabled-bar");
        }
    }

    // ---------------------------------------------------------------- таймер (вызывается из StartManager)

    void resetWarnings() {
        netherWarned = Integer.MAX_VALUE;
        endWarned = Integer.MAX_VALUE;
    }

    void checkUnlocks(long now) {
        netherWarned = checkUnlock(Dim.NETHER, now, netherWarned);
        endWarned = checkUnlock(Dim.END, now, endWarned);
    }

    private int checkUnlock(Dim dim, long now, int warned) {
        long at = unlockAt(dim);
        if (at == 0 || !enabledFlag(dim) || announced(dim)) {
            return warned;
        }
        if (now >= at) {
            announced(dim, true);
            plugin.state().save();
            announceOpened(dim);
            return warned;
        }
        long minutesLeft = (at - now + 59_999) / 60_000;
        int stage = Integer.MAX_VALUE;
        for (int warn : WARN_MINUTES) {
            if (minutesLeft <= warn) {
                stage = Math.min(stage, warn);
            }
        }
        if (stage < warned) {
            long seconds = (at - now + 999) / 1000;
            plugin.lang().broadcast("dimension." + dim.key + "-soon",
                    viewer -> Lang.ph("left", plugin.lang().duration(viewer, seconds)));
            return stage;
        }
        return warned;
    }

    private boolean announced(Dim dim) {
        return dim == Dim.NETHER ? plugin.state().netherAnnounced() : plugin.state().endAnnounced();
    }

    private void announced(Dim dim, boolean value) {
        if (dim == Dim.NETHER) {
            plugin.state().netherAnnounced(value);
        } else {
            plugin.state().endAnnounced(value);
        }
    }

    private void announceOpened(Dim dim) {
        String key = "dimension." + dim.key + "-opened";
        plugin.lang().broadcast(key);
        Title.Times times = Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(800));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(net.kyori.adventure.text.Component.empty(), plugin.lang().get(player, key), times));
            player.playSound(player, dim == Dim.NETHER ? Sound.BLOCK_PORTAL_TRIGGER : Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 1f);
        }
    }

    // ---------------------------------------------------------------- выселение

    /** Выводит игроков из закрытых измерений. */
    public void evacuateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            evacuate(player);
        }
    }

    private void evacuate(Player player) {
        if (allowed(player.getWorld().getEnvironment()) || exempt(player)) {
            return;
        }
        Location target = player.getRespawnLocation();
        if (target == null || !allowed(target.getWorld().getEnvironment())) {
            target = plugin.mainWorld().getSpawnLocation();
        }
        player.leaveVehicle();
        player.teleport(target);
        plugin.lang().send(player, "dimension.evacuated");
    }

    private static boolean exempt(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    // ---------------------------------------------------------------- события

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        if (exempt(player)) {
            return;
        }
        if (lobbyBlocksPortals()) {
            event.setCancelled(true);
            plugin.lang().actionBar(player, "dimension.portals-after-start");
            return;
        }
        Location to = event.getTo();
        Dim dim = to.getWorld() != null ? of(to.getWorld().getEnvironment()) : null;
        if (dim != null && !open(dim)) {
            event.setCancelled(true);
            denyBar(player, dim);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        Location to = event.getTo();
        if (lobbyBlocksPortals() || (to != null && to.getWorld() != null && !allowed(to.getWorld().getEnvironment()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getReason() != PortalCreateEvent.CreateReason.FIRE) {
            return;
        }
        if (event.getEntity() instanceof Player player && exempt(player)) {
            return;
        }
        if (!open(Dim.NETHER) || lobbyBlocksPortals()) {
            event.setCancelled(true);
            if (event.getEntity() instanceof Player player) {
                if (lobbyBlocksPortals()) {
                    plugin.lang().actionBar(player, "dimension.ignite-blocked");
                } else {
                    denyBar(player, Dim.NETHER);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEyeOfEnder(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (event.getClickedBlock().getType() != Material.END_PORTAL_FRAME || event.getItem() == null
                || event.getItem().getType() != Material.ENDER_EYE || exempt(event.getPlayer())) {
            return;
        }
        if (lobbyBlocksPortals()) {
            event.setCancelled(true);
            plugin.lang().actionBar(event.getPlayer(), "dimension.portals-after-start");
        } else if (!open(Dim.END)) {
            event.setCancelled(true);
            denyBar(event.getPlayer(), Dim.END);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                evacuate(event.getPlayer());
            }
        }, 5L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        evacuate(event.getPlayer());
    }
}
