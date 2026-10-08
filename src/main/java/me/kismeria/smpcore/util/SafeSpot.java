package me.kismeria.smpcore.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;

/** Поиск безопасной точки для телепорта. */
public final class SafeSpot {

    private SafeSpot() {
    }

    /** Ближайшая к {@code near} безопасная точка внутри границы мира. */
    public static Location insideBorder(Location near) {
        World world = near.getWorld();
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = border.getSize() / 2.0 - 1.5;
        double x = center.getX();
        double z = center.getZ();
        if (half > 0.5) {
            x = clamp(near.getX(), center.getX() - half, center.getX() + half);
            z = clamp(near.getZ(), center.getZ() - half, center.getZ() + half);
        }
        Location spot = stand(world, (int) Math.floor(x), (int) Math.floor(z), near.getBlockY(), world.getMaxHeight());
        spot.setYaw(near.getYaw());
        spot.setPitch(near.getPitch());
        return spot;
    }

    /** Безопасная точка в колонне x/z ниже потолка {@code ceilingY}, ищет вокруг {@code preferY}. */
    public static Location stand(World world, int x, int z, int preferY, int ceilingY) {
        int min = world.getMinHeight() + 1;
        int max = Math.min(world.getMaxHeight() - 2, ceilingY - 2);
        int start = Math.max(min, Math.min(max, preferY));
        for (int d = 0; d <= max - min; d++) {
            int up = start + d;
            if (up <= max && canStand(world, x, up, z)) {
                return new Location(world, x + 0.5, up, z + 0.5);
            }
            int down = start - d;
            if (d > 0 && down >= min && canStand(world, x, down, z)) {
                return new Location(world, x + 0.5, down, z + 0.5);
            }
        }
        int top = world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5, Math.min(top, max), z + 0.5);
    }

    public static boolean canStand(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable()
                && !feet.isLiquid() && !head.isLiquid()
                && ground.getType().isSolid() && !dangerous(ground.getType()) && !dangerous(feet.getType());
    }

    private static boolean dangerous(Material type) {
        return switch (type) {
            case LAVA, MAGMA_BLOCK, CACTUS, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE, SWEET_BERRY_BUSH, WITHER_ROSE, POWDER_SNOW, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }

    /** Хитбокс сущности, если её ноги поставить в {@code at}. */
    public static BoundingBox boxAt(Entity entity, Location at) {
        double halfWidth = entity.getWidth() / 2.0;
        return new BoundingBox(at.getX() - halfWidth, at.getY(), at.getZ() - halfWidth,
                at.getX() + halfWidth, at.getY() + entity.getHeight(), at.getZ() + halfWidth);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
