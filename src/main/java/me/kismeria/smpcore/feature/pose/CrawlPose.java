package me.kismeria.smpcore.feature.pose;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleSwimEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * /crawl — порт GSit (mcv v26_3 Crawl): игрок «плывёт» на суше, а над головой у него в его клиенте
 * висит невидимый шалкер, который не даёт встать.
 */
public final class CrawlPose {

    private final Plugin plugin;
    private final Player player;
    private final Runnable onStop;
    private final ServerPlayer serverPlayer;
    private final BoxEntity box;
    private boolean boxExists = false;
    private boolean finished = false;
    private final Listener listener;
    private Listener moveListener;

    public CrawlPose(Plugin plugin, Player player, Runnable onStop) {
        this.plugin = plugin;
        this.player = player;
        this.onStop = onStop;
        serverPlayer = ((CraftPlayer) player).getHandle();
        box = new BoxEntity(player.getLocation());
        listener = new Listener() {
            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void onSwim(EntityToggleSwimEvent event) {
                if (event.getEntity() == player) event.setCancelled(true);
            }
        };
    }

    @SuppressWarnings("deprecation")
    public void start() {
        player.setSwimming(true);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (finished) return;
            moveListener = new Listener() {
                @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
                public void onMove(PlayerMoveEvent event) {
                    if (event.getPlayer() != player) return;
                    Location from = event.getFrom(), to = event.getTo();
                    if (from.getX() != to.getX() || from.getZ() != to.getZ() || from.getY() != to.getY()) tick(to);
                }
            };
            Bukkit.getPluginManager().registerEvents(moveListener, plugin);
            tick(player.getLocation());
        }, 1L);
    }

    private void tick(Location location) {
        if (finished) return;
        if (serverPlayer.isInWater() || player.isFlying()) {
            onStop.run();
            return;
        }

        Location tickLocation = location.clone();
        Block locationBlock = tickLocation.getBlock();
        int blockSize = (int) ((tickLocation.getY() - tickLocation.getBlockY()) * 100);
        tickLocation.setY(tickLocation.getBlockY() + (blockSize >= 40 ? 2.49 : 1.49));
        Block aboveBlock = tickLocation.getBlock();
        boolean solidAbove = aboveBlock.getBoundingBox().contains(tickLocation.toVector())
                && !aboveBlock.getCollisionShape().getBoundingBoxes().isEmpty();
        if (solidAbove) {
            destroyBox();
            return;
        }

        Location at = location.clone();
        int height = locationBlock.getBoundingBox().getHeight() >= 0.4 || at.getY() % 0.015625 == 0.0
                ? (player.getFallDistance() > 0.7 ? 0 : blockSize) : 0;
        at.setY(at.getY() + (height >= 40 ? 1.5 : 0.5));
        box.setRawPeekAmount(height >= 40 ? 100 - height : 0);

        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(2);
        if (!boxExists) {
            box.setPos(at.getX(), at.getY(), at.getZ());
            packets.add(new ClientboundAddEntityPacket(box.getId(), box.getUUID(), box.getX(), box.getY(), box.getZ(), box.getXRot(),
                    box.getYRot(), box.getType(), 0, box.getDeltaMovement(), box.getYHeadRot()));
            packets.add(new ClientboundSetEntityDataPacket(box.getId(), box.getEntityData().getNonDefaultValues()));
        } else {
            packets.add(new ClientboundSetEntityDataPacket(box.getId(), box.getEntityData().getNonDefaultValues()));
            box.setPosRaw(at.getX(), at.getY(), at.getZ());
            packets.add(new ClientboundTeleportEntityPacket(box.getId(), PositionMoveRotation.of(box), Set.of(), false));
        }
        serverPlayer.connection.send(new ClientboundBundlePacket(packets));
        boxExists = true;
    }

    @SuppressWarnings("deprecation")
    public void stop() {
        finished = true;
        HandlerList.unregisterAll(listener);
        if (moveListener != null) HandlerList.unregisterAll(moveListener);
        player.setSwimming(false);
        destroyBox();
    }

    private void destroyBox() {
        if (!boxExists) return;
        serverPlayer.connection.send(new ClientboundRemoveEntitiesPacket(box.getId()));
        boxExists = false;
    }
}
