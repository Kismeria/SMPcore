package me.kismeria.smpcore.feature.pose;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;

import java.lang.reflect.Field;
import java.util.List;

/** Прокладка из GSit: между игроком и тем, кто сидит у него на голове; ещё прячет ник лежащего NPC. */
public class PlayerSitEntity extends AreaEffectCloud {

    private static Field vehicleField;

    public PlayerSitEntity(Location location) {
        super(((CraftWorld) location.getWorld()).getHandle(), location.getX(), location.getY(), location.getZ());
        persist = false;
        setRadius(0);
        setDuration(Integer.MAX_VALUE);
        setNoGravity(true);
        setPermanentlyInvulnerable(true);
    }

    @Override
    public void tick() {
    }

    @Override
    protected void handlePortal() {
    }

    @Override
    public boolean dismountsUnderwater() {
        return false;
    }

    /** Посадить на сущность только для пакетов: без событий и без настоящего спавна. */
    public void setVehicle(Entity vehicle) {
        try {
            if (vehicleField == null) {
                for (Field field : Entity.class.getDeclaredFields()) {
                    if (field.getType().equals(Entity.class)) {
                        field.setAccessible(true);
                        vehicleField = field;
                        break;
                    }
                }
            }
            vehicleField.set(this, vehicle);
        } catch (Throwable ignored) {
        }
        if (vehicle.passengers.isEmpty()) {
            vehicle.passengers = ImmutableList.of(this);
        } else {
            List<Entity> list = Lists.newArrayList(vehicle.passengers);
            list.add(this);
            vehicle.passengers = ImmutableList.copyOf(list);
        }
    }
}
