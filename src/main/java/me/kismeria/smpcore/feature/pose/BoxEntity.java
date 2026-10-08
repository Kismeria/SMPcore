package me.kismeria.smpcore.feature.pose;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Shulker;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;

/** Невидимый шалкер над головой ползущего, только в его клиенте (как в GSit). */
public class BoxEntity extends Shulker {

    public BoxEntity(Location location) {
        super(EntityTypes.SHULKER, ((CraftWorld) location.getWorld()).getHandle());
        setPos(location.getX(), location.getY(), location.getZ());
        persist = false;
        setInvisible(true);
        setNoGravity(true);
        setPermanentlyInvulnerable(true);
        setNoAi(true);
        setSilent(true);
        setAttachFace(Direction.UP);
    }

    @Override
    protected void handlePortal() {
    }

    @Override
    public boolean isAffectedByFluids() {
        return false;
    }
}
