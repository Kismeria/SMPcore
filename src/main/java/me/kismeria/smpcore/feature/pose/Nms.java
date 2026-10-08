package me.kismeria.smpcore.feature.pose;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.logging.Logger;

/** Сущности GSit: спавн мимо событий и телепорт без проверок, как в GSit. */
public final class Nms {

    private static final Field ENTITY_MANAGER;

    static {
        Field found = null;
        for (Field field : ServerLevel.class.getDeclaredFields()) {
            if (field.getType().equals(PersistentEntitySectionManager.class)) {
                field.setAccessible(true);
                found = field;
                break;
            }
        }
        ENTITY_MANAGER = found;
    }

    private Nms() {
    }

    @SuppressWarnings("unchecked")
    static boolean spawn(net.minecraft.world.entity.Entity entity, Logger log) {
        try {
            if (ENTITY_MANAGER != null) {
                return ((PersistentEntitySectionManager<net.minecraft.world.entity.Entity>) ENTITY_MANAGER
                        .get(entity.level().getWorld().getHandle())).addNewEntity(entity);
            }
            Object getter = entity.level().getEntities();
            return (boolean) getter.getClass().getMethod("addNewEntity", net.minecraft.world.entity.Entity.class).invoke(getter, entity);
        } catch (Throwable e) {
            log.warning("Не удалось заспавнить сиденье: " + e);
            return false;
        }
    }

    public static void setLocation(Entity entity, Location location) {
        if (entity instanceof Player) {
            ServerGamePacketListenerImpl connection = ((CraftPlayer) entity).getHandle().connection;
            connection.internalTeleport(new PositionMoveRotation(new Vec3(location.getX(), location.getY(), location.getZ()),
                    Vec3.ZERO, location.getYaw(), location.getPitch()), Collections.emptySet());
            connection.resetPosition();
        } else {
            ((CraftEntity) entity).getHandle().snapTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        }
    }

    /** Сиденье с седоком; null — не вышло. */
    public static SeatEntity createSeat(Location location, Entity rider, boolean canRotate, Logger log) {
        if (!rider.isValid()) {
            return null;
        }
        SeatEntity seat = new SeatEntity(location);
        boolean riding = ((CraftEntity) rider).getHandle().startRiding(seat, true, true);
        if (!riding || !spawn(seat, log)) {
            seat.discard();
            return null;
        }
        if (canRotate) {
            seat.startRotate();
        }
        return seat;
    }

    /** Игрок садится на голову target через прокладку; null — не вышло. */
    public static PlayerSitEntity sitOnPlayer(Player player, Player target, Logger log) {
        PlayerSitEntity spacer = new PlayerSitEntity(target.getLocation());
        if (!spacer.startRiding(((CraftEntity) target).getHandle(), true, true)) {
            spacer.discard();
            return null;
        }
        if (!((CraftEntity) player).getHandle().startRiding(spacer, true, true) || !spawn(spacer, log)) {
            spacer.stopRiding(true);
            spacer.discard();
            return null;
        }
        return spacer;
    }
}
