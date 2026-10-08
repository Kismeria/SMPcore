package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.feature.pose.CrawlPose;
import me.kismeria.smpcore.feature.pose.LayPose;
import me.kismeria.smpcore.feature.pose.Nms;
import me.kismeria.smpcore.feature.pose.PlayerSitEntity;
import me.kismeria.smpcore.feature.pose.SeatEntity;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /sit, /lay, /crawl и посадка на голову игрока (ПКМ пустой рукой) — перенесено из GSit (модуль 26.3):
 * сиденье — невидимая стойка-маркер, лёжа — пакетный спящий NPC, ползком — пакетный шалкер над головой.
 * Встать — Shift.
 */
public final class Poses implements TabExecutor, Listener {

    private enum Kind { SIT, LAY, CRAWL, HEAD }

    /** Смещение сиденья GSit для 1.20.2+. */
    private static final double BASE_OFFSET = -0.025d;
    private static final double STAIR_XZ_OFFSET = 0.123d;
    private static final double STAIR_Y_OFFSET = 0.5d;

    /** Сиденье: блок, точка, сущность и (для лёжа) NPC. Для HEAD seat — прокладка на голове. */
    private static final class Seat {
        final Kind kind;
        final Block block;
        final Location location;
        final Entity entity;
        LayPose lay;
        CrawlPose crawl;
        UUID carrier;

        Seat(Kind kind, Block block, Location location, Entity entity) {
            this.kind = kind;
            this.block = block;
            this.location = location;
            this.entity = entity;
        }
    }

    private final SmpCore plugin;
    private final NamespacedKey noHeadKey;
    private final Map<UUID, Seat> posing = new HashMap<>();
    /** Сброс седока: столько нажатий Shift, каждое не позже чем через EJECT_WINDOW_MS после прошлого. */
    private static final int EJECT_PRESSES = 3;
    private static final long EJECT_WINDOW_MS = 400;
    /** [число нажатий подряд, время последнего]. */
    private final Map<UUID, long[]> ejectPresses = new HashMap<>();

    public Poses(SmpCore plugin) {
        this.plugin = plugin;
        this.noHeadKey = new NamespacedKey(plugin, "no_head_sit");
    }

    public void disable() {
        for (UUID id : List.copyOf(posing.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                stand(player, true);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "invsee.only-players");
            return true;
        }
        if (command.getName().equals("sit") && args.length > 0 && args[0].equalsIgnoreCase("players")) {
            boolean deny = !player.getPersistentDataContainer().has(noHeadKey);
            if (deny) {
                player.getPersistentDataContainer().set(noHeadKey, PersistentDataType.BYTE, (byte) 1);
            } else {
                player.getPersistentDataContainer().remove(noHeadKey);
            }
            plugin.lang().send(player, deny ? "pose.head-disabled" : "pose.head-enabled");
            return true;
        }
        Kind kind = switch (command.getName()) {
            case "lay" -> Kind.LAY;
            case "crawl" -> Kind.CRAWL;
            default -> Kind.SIT;
        };
        Seat current = posing.get(player.getUniqueId());
        if (current != null) {
            stand(player, true);
            if (current.kind == kind) {
                return true;
            }
        }
        if (kind == Kind.CRAWL) {
            if (!player.isValid() || player.isInsideVehicle() || player.isFlying() || player.isInWater()
                    || player.isSleeping() || player.getGameMode() == GameMode.SPECTATOR) {
                plugin.lang().actionBar(player, "pose.cannot");
                return true;
            }
            crawl(player);
            return true;
        }
        if (!player.isValid() || player.isSneaking() || !player.isOnGround() || player.isInsideVehicle()
                || player.isSleeping() || player.getGameMode() == GameMode.SPECTATOR) {
            plugin.lang().actionBar(player, "pose.cannot");
            return true;
        }
        // блок под игроком, как в GSit
        Location feet = player.getLocation();
        Block block = feet.getBlock().isPassable() ? feet.clone().subtract(0, 0.0625, 0).getBlock() : feet.getBlock();
        boolean overSize = false;
        for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
            if (box.getMaxY() > 1.25) {
                overSize = true;
            }
        }
        if (!block.getRelative(BlockFace.UP).isPassable() || overSize || block.isPassable()) {
            plugin.lang().actionBar(player, "pose.cannot");
            return true;
        }
        if (kind == Kind.LAY) {
            lay(player, block);
        } else if (block.getBlockData() instanceof Stairs stairs && stairs.getHalf() == Bisected.Half.BOTTOM) {
            sitOnStairs(player, block, stairs);
        } else {
            sit(player, block, true, 0, 0, 0, player.getLocation().getYaw());
        }
        return true;
    }

    // ---------------------------------------------------------------- позы

    /** Точка сиденья GSit (center-block: true). */
    private static Location seatLocation(Block block, double xOffset, double yOffset, double zOffset) {
        BoundingBox box = block.getBoundingBox();
        double top = box.getMinY() + box.getHeight();
        double additional = top == 0d ? 1d : top - block.getY();
        return block.getLocation().add(0.5d + xOffset, yOffset - BASE_OFFSET + additional, 0.5d + zOffset);
    }

    private Seat createSeat(Kind kind, Player player, Block block, boolean canRotate, double x, double y, double z, float yaw) {
        Location at = seatLocation(block, x, y, z);
        at.setYaw(yaw);
        SeatEntity entity = Nms.createSeat(at, player, canRotate, plugin.getLogger());
        if (entity == null) {
            return null;
        }
        Seat seat = new Seat(kind, block, at, entity.getBukkitEntity());
        posing.put(player.getUniqueId(), seat);
        plugin.lang().actionBar(player, "pose.stand-hint");
        return seat;
    }

    private void sit(Player player, Block block, boolean canRotate, double x, double y, double z, float yaw) {
        createSeat(Kind.SIT, player, block, canRotate, x, y, z, yaw);
    }

    private void sitOnStairs(Player player, Block block, Stairs stairs) {
        BlockFace face = stairs.getFacing().getOppositeFace();
        if (stairs.getShape() == Stairs.Shape.STRAIGHT) {
            switch (face) {
                case EAST -> sit(player, block, false, STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, 0d, -90f);
                case SOUTH -> sit(player, block, false, 0d, -STAIR_Y_OFFSET, STAIR_XZ_OFFSET, 0f);
                case WEST -> sit(player, block, false, -STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, 0d, 90f);
                case NORTH -> sit(player, block, false, 0d, -STAIR_Y_OFFSET, -STAIR_XZ_OFFSET, 180f);
                default -> {
                }
            }
            return;
        }
        Stairs.Shape shape = stairs.getShape();
        boolean right = shape == Stairs.Shape.OUTER_RIGHT || shape == Stairs.Shape.INNER_RIGHT;
        boolean left = !right;
        if (face == BlockFace.NORTH && right || face == BlockFace.EAST && left) {
            sit(player, block, false, STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, -STAIR_XZ_OFFSET, -135f);
        } else if (face == BlockFace.NORTH || face == BlockFace.WEST && right) {
            sit(player, block, false, -STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, -STAIR_XZ_OFFSET, 135f);
        } else if (face == BlockFace.SOUTH && right || face == BlockFace.WEST) {
            sit(player, block, false, -STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, STAIR_XZ_OFFSET, 45f);
        } else {
            sit(player, block, false, STAIR_XZ_OFFSET, -STAIR_Y_OFFSET, STAIR_XZ_OFFSET, -45f);
        }
    }

    private void lay(Player player, Block block) {
        Seat seat = createSeat(Kind.LAY, player, block, true, 0, 0, 0, player.getLocation().getYaw());
        if (seat == null) {
            return;
        }
        seat.lay = new LayPose(plugin, player, (SeatEntity) ((org.bukkit.craftbukkit.entity.CraftEntity) seat.entity).getHandle(),
                seat.location, BASE_OFFSET);
        seat.lay.spawn();
    }

    private void crawl(Player player) {
        Seat seat = new Seat(Kind.CRAWL, null, null, null);
        seat.crawl = new CrawlPose(plugin, player, () -> stand(player, false));
        posing.put(player.getUniqueId(), seat);
        seat.crawl.start();
        plugin.lang().actionBar(player, "pose.stand-hint");
    }

    /** Встать. safe — вернуть на поверхность, на которой сидел (как GSit get-up-return: false). */
    private void stand(Player player, boolean safe) {
        Seat seat = posing.remove(player.getUniqueId());
        if (seat == null) {
            return;
        }
        switch (seat.kind) {
            case SIT, LAY -> {
                if (safe && player.isValid()) {
                    double stairs = seat.block.getBlockData() instanceof Stairs ? STAIR_Y_OFFSET : 0d;
                    Location back = seat.location.clone().add(0d, BASE_OFFSET + stairs, 0d);
                    back.setYaw(player.getLocation().getYaw());
                    back.setPitch(player.getLocation().getPitch());
                    Nms.setLocation(player, back);
                }
                if (seat.lay != null) {
                    seat.lay.remove();
                }
                seat.entity.remove();
            }
            case HEAD -> {
                player.leaveVehicle();
                seat.entity.remove();
            }
            case CRAWL -> seat.crawl.stop();
        }
    }

    /** Сбросить всех, кто сидит у игрока на голове. */
    private void ejectRiders(Player carrier) {
        for (Map.Entry<UUID, Seat> entry : List.copyOf(posing.entrySet())) {
            if (entry.getValue().kind == Kind.HEAD && carrier.getUniqueId().equals(entry.getValue().carrier)) {
                Player rider = plugin.getServer().getPlayer(entry.getKey());
                if (rider != null) {
                    stand(rider, false);
                }
            }
        }
    }

    // ---------------------------------------------------------------- события

    /** Слезть Shift-ом: это dismount с сиденья или прокладки. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Seat seat = posing.get(player.getUniqueId());
        if (seat != null && seat.entity == event.getDismounted()) {
            stand(player, true);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        Seat seat = posing.get(player.getUniqueId());
        if (seat != null && seat.kind == Kind.CRAWL) {
            stand(player, false);
        } else if (seat == null && player.isOnGround()) {
            // три быстрых Shift стоя — сбросить седока с головы (одним случайно не скинешь)
            long now = System.currentTimeMillis();
            long[] presses = ejectPresses.computeIfAbsent(player.getUniqueId(), id -> new long[2]);
            presses[0] = now - presses[1] <= EJECT_WINDOW_MS ? presses[0] + 1 : 1;
            presses[1] = now;
            if (presses[0] >= EJECT_PRESSES) {
                presses[0] = 0;
                ejectRiders(player);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        for (Map.Entry<UUID, Seat> entry : List.copyOf(posing.entrySet())) {
            if (event.getBlock().equals(entry.getValue().block)) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    stand(player, true);
                }
            }
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Seat seat = posing.get(event.getPlayer().getUniqueId());
        if (seat != null && seat.kind == Kind.CRAWL) {
            stand(event.getPlayer(), false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ejectPresses.remove(event.getPlayer().getUniqueId());
        stand(event.getPlayer(), true);
        ejectRiders(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        stand(event.getPlayer(), false);
        ejectRiders(event.getPlayer());
    }

    /** ПКМ пустой рукой по игроку — сесть ему на голову. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClickPlayer(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)
                || !plugin.flag("poses.sit-on-players")) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking() || !player.getInventory().getItemInMainHand().getType().isAir()
                || posing.containsKey(player.getUniqueId()) || player.isInsideVehicle()
                || player.getGameMode() == GameMode.SPECTATOR || target.getGameMode() == GameMode.SPECTATOR
                || !player.canSee(target) || !target.getPassengers().isEmpty() || posing.containsKey(target.getUniqueId())
                || target.isInsideVehicle()) {
            return;
        }
        if (target.getPersistentDataContainer().has(noHeadKey)) {
            plugin.lang().actionBar(player, "pose.head-denied", Lang.txt("player", target.getName()));
            return;
        }
        event.setCancelled(true);
        PlayerSitEntity spacer = Nms.sitOnPlayer(player, target, plugin.getLogger());
        if (spacer == null) {
            return;
        }
        Seat seat = new Seat(Kind.HEAD, null, null, spacer.getBukkitEntity());
        seat.carrier = target.getUniqueId();
        posing.put(player.getUniqueId(), seat);
        plugin.lang().actionBar(player, "pose.stand-hint");
        plugin.lang().actionBar(target, "pose.head-carrier", Lang.txt("player", player.getName()));
    }

    /** ПКМ пустой рукой по нижней ступеньке или полублоку — сесть. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClickStairs(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND
                || event.getClickedBlock() == null || !plugin.flag("poses.sit-on-stairs")) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking() || !player.getInventory().getItemInMainHand().getType().isAir()
                || posing.containsKey(player.getUniqueId()) || player.isInsideVehicle() || !player.isOnGround()
                || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Block block = event.getClickedBlock();
        BlockData data = block.getBlockData();
        boolean stairs = data instanceof Stairs s && s.getHalf() == Bisected.Half.BOTTOM;
        boolean slab = data instanceof Slab s && s.getType() == Slab.Type.BOTTOM;
        if (!stairs && !slab || !block.getRelative(BlockFace.UP).isPassable()
                || player.getLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > 3 * 3) {
            return;
        }
        for (Seat other : posing.values()) {
            if (block.equals(other.block)) {
                return;
            }
        }
        event.setCancelled(true);
        if (stairs) {
            sitOnStairs(player, block, (Stairs) data);
        } else {
            sit(player, block, true, 0, 0, 0, player.getLocation().getYaw());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equals("sit") && args.length == 1 && "players".startsWith(args[0].toLowerCase())) {
            return List.of("players");
        }
        return List.of();
    }
}
