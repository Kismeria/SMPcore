package me.kismeria.smpcore.feature.pose;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * /lay — порт GSit (mcv v26_3 Pose, тип LAY): игрок невидим и сидит на сиденье, а всем вокруг (и ему самому)
 * пакетами показывается спящий NPC с его скином и вещами на фальшивой кровати под миром.
 */
public final class LayPose {

    private static final EntityDataAccessor<net.minecraft.world.entity.Pose> POSE_ACCESSOR = EntityDataSerializers.POSE.createAccessor(6);
    private static final EntityDataAccessor<Optional<BlockPos>> SLEEP_BLOCK_POS_ACCESSOR = EntityDataSerializers.OPTIONAL_BLOCK_POS.createAccessor(14);
    private static final EntityDataAccessor<HumanoidArm> MAIN_HAND_ACCESSOR = EntityDataSerializers.HUMANOID_ARM.createAccessor(15);
    private static final EntityDataAccessor<Byte> SKIN_ACCESSOR = EntityDataSerializers.BYTE.createAccessor(16);
    private static final EntityDataAccessor<OptionalInt> LEFT_SHOULDER_ACCESSOR = EntityDataSerializers.OPTIONAL_UNSIGNED_INT.createAccessor(19);
    private static final EntityDataAccessor<OptionalInt> RIGHT_SHOULDER_ACCESSOR = EntityDataSerializers.OPTIONAL_UNSIGNED_INT.createAccessor(20);

    private final Plugin plugin;
    private final SeatEntity seatEntity;
    private final Location seatLocation;
    private final Player seatPlayer;
    private Set<Player> nearbyPlayers = new HashSet<>();
    private final ServerPlayer serverPlayer;
    private final ServerPlayer playerNpc;
    private final PlayerSitEntity hideNameEntity;
    private final Location blockLocation;
    private final Block bedBlock;
    private final Direction direction;
    private final ClientboundBlockUpdatePacket setBedPacket;
    private final ClientboundPlayerInfoUpdatePacket addNpcInfoPacket;
    private final ClientboundPlayerInfoRemovePacket removeNpcInfoPacket;
    private final ClientboundRemoveEntitiesPacket removeNpcPacket;
    private final ClientboundAddEntityPacket createNpcPacket;
    private final ClientboundTeleportEntityPacket teleportNpcPacket;
    private ClientboundBundlePacket bundle;
    private NonNullList<ItemStack> equipmentSlotCache;
    private ItemStack mainSlotCache;
    private float directionCache;
    private boolean sleepingIgnoredCache;
    private final int renderRange;
    private final Listener listener;

    public LayPose(Plugin plugin, Player player, SeatEntity seatEntity, Location seatLocation, double baseOffset) {
        this.plugin = plugin;
        this.seatEntity = seatEntity;
        this.seatLocation = seatLocation;
        seatPlayer = player;
        serverPlayer = ((CraftPlayer) player).getHandle();
        renderRange = player.getWorld().getSimulationDistance() * 16;

        blockLocation = seatLocation.clone();
        blockLocation.setY(blockLocation.getWorld().getMinHeight());
        bedBlock = blockLocation.getBlock();
        BlockPos bedPos = new BlockPos(blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ());

        playerNpc = createNpc();
        double offset = seatLocation.getY() + baseOffset + 0.1125d * serverPlayer.getScale();
        playerNpc.absSnapTo(seatLocation.getX(), offset, seatLocation.getZ(), 0f, 0f);
        playerNpc.getEntityData().set(SLEEP_BLOCK_POS_ACCESSOR, Optional.of(bedPos));

        direction = getDirection();
        setBedPacket = new ClientboundBlockUpdatePacket(bedPos, Blocks.BED.white().defaultBlockState()
                .setValue(BedBlock.FACING, direction.getOpposite()).setValue(BedBlock.PART, BedPart.HEAD));
        addNpcInfoPacket = new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                Collections.singletonList(playerNpc));
        removeNpcInfoPacket = new ClientboundPlayerInfoRemovePacket(Collections.singletonList(playerNpc.getUUID()));
        removeNpcPacket = new ClientboundRemoveEntitiesPacket(playerNpc.getId());
        createNpcPacket = new ClientboundAddEntityPacket(playerNpc.getId(), playerNpc.getUUID(), playerNpc.getX(), playerNpc.getY(),
                playerNpc.getZ(), playerNpc.getXRot(), playerNpc.getYRot(), playerNpc.getType(), 0, playerNpc.getDeltaMovement(), playerNpc.getYHeadRot());
        teleportNpcPacket = new ClientboundTeleportEntityPacket(playerNpc.getId(), PositionMoveRotation.of(playerNpc), Set.of(), false);

        hideNameEntity = new PlayerSitEntity(player.getLocation());

        listener = new Listener() {
            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void onInteract(PlayerInteractEvent event) {
                if (event.getPlayer() == seatPlayer) event.setCancelled(true);
            }

            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void onInteractEntity(PlayerInteractEntityEvent event) {
                if (event.getPlayer() == seatPlayer) event.setCancelled(true);
            }

            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void onAttack(EntityDamageByEntityEvent event) {
                if (event.getDamager() == seatPlayer) event.setCancelled(true);
            }

            @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
            public void onDamage(EntityDamageEvent event) {
                if (event.getEntity() == seatPlayer) playDamageAnimation();
            }

            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void onLaunch(ProjectileLaunchEvent event) {
                if (event.getEntity().getShooter() == seatPlayer) event.setCancelled(true);
            }

            @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
            public void onAnimation(PlayerAnimationEvent event) {
                if (event.getPlayer() == seatPlayer) {
                    playHandAnimation(event.getAnimationType() == PlayerAnimationType.ARM_SWING ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                }
            }

            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void onClick(InventoryClickEvent event) {
                if (event.getWhoClicked() == seatPlayer && seatPlayer.getGameMode() == GameMode.CREATIVE) event.setCancelled(true);
            }

            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void onDrop(PlayerDropItemEvent event) {
                if (event.getPlayer() == seatPlayer && seatPlayer.getGameMode() == GameMode.CREATIVE) event.setCancelled(true);
            }

            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void onPotion(EntityPotionEffectEvent event) {
                if (event.getEntity() == seatPlayer) serverPlayer.setInvisible(true);
            }
        };
    }

    public void spawn() {
        nearbyPlayers = getNearbyPlayers();

        playerNpc.setGlowingTag(serverPlayer.hasGlowingTag());
        if (serverPlayer.hasGlowingTag()) serverPlayer.setGlowingTag(false);

        playerNpc.getEntityData().set(POSE_ACCESSOR, net.minecraft.world.entity.Pose.SLEEPING);
        playerNpc.getEntityData().set(MAIN_HAND_ACCESSOR, serverPlayer.getEntityData().get(MAIN_HAND_ACCESSOR));
        playerNpc.getEntityData().set(SKIN_ACCESSOR, serverPlayer.getEntityData().get(SKIN_ACCESSOR));
        playerNpc.getEntityData().set(LEFT_SHOULDER_ACCESSOR, serverPlayer.getEntityData().get(LEFT_SHOULDER_ACCESSOR));
        playerNpc.getEntityData().set(RIGHT_SHOULDER_ACCESSOR, serverPlayer.getEntityData().get(RIGHT_SHOULDER_ACCESSOR));
        serverPlayer.getEntityData().set(LEFT_SHOULDER_ACCESSOR, OptionalInt.empty());
        serverPlayer.getEntityData().set(RIGHT_SHOULDER_ACCESSOR, OptionalInt.empty());

        serverPlayer.setInvisible(true);
        setEquipmentVisibility(false);

        sleepingIgnoredCache = seatPlayer.isSleepingIgnored();
        if (!sleepingIgnoredCache) seatPlayer.setSleepingIgnored(true);
        seatPlayer.setStatistic(Statistic.TIME_SINCE_REST, 0);

        SynchedEntityData data = playerNpc.getEntityData();
        ClientboundSetEntityDataPacket metaNpcPacket = new ClientboundSetEntityDataPacket(playerNpc.getId(),
                data.isDirty() ? data.packDirty() : data.getNonDefaultValues());
        ClientboundUpdateAttributesPacket attributeNpcPacket = new ClientboundUpdateAttributesPacket(playerNpc.getId(),
                serverPlayer.getAttributes().getSyncableAttributes());

        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        packets.add(addNpcInfoPacket);
        packets.add(createNpcPacket);
        packets.add(setBedPacket);
        packets.add(metaNpcPacket);
        packets.add(attributeNpcPacket);
        bundle = new ClientboundBundlePacket(packets);

        for (Player nearbyPlayer : nearbyPlayers) addViewerPlayer(nearbyPlayer);

        // самому лежащему прячем ник NPC: на него «садится» облако
        hideNameEntity.setVehicle(playerNpc);
        List<Packet<? super ClientGamePacketListener>> playerPackets = new ArrayList<>();
        playerPackets.add(new ClientboundAddEntityPacket(hideNameEntity.getId(), hideNameEntity.getUUID(), hideNameEntity.getX(),
                hideNameEntity.getY(), hideNameEntity.getZ(), hideNameEntity.getXRot(), hideNameEntity.getYRot(), hideNameEntity.getType(),
                0, hideNameEntity.getDeltaMovement(), hideNameEntity.getYHeadRot()));
        playerPackets.add(new ClientboundSetEntityDataPacket(hideNameEntity.getId(), hideNameEntity.getEntityData().getNonDefaultValues()));
        playerPackets.add(new ClientboundSetPassengersPacket(playerNpc));
        serverPlayer.connection.send(new ClientboundBundlePacket(playerPackets));

        Bukkit.getPluginManager().registerEvents(listener, plugin);

        seatEntity.setRunnable(() -> {
            Set<Player> current = getNearbyPlayers();
            for (Player nearbyPlayer : current) {
                if (nearbyPlayers.add(nearbyPlayer)) addViewerPlayer(nearbyPlayer);
            }
            for (Player nearbyPlayer : new ArrayList<>(nearbyPlayers)) {
                if (!current.contains(nearbyPlayer)) {
                    nearbyPlayers.remove(nearbyPlayer);
                    removeViewerPlayer(nearbyPlayer);
                }
            }
            updateDirection();
            serverPlayer.setInvisible(true);
            updateEquipment();
            setEquipmentVisibility(false);
            updateSkin();
        });
    }

    private void addViewerPlayer(Player player) {
        send(player, bundle);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            send(player, teleportNpcPacket);
            Bukkit.getScheduler().runTaskLater(plugin, () -> send(player, teleportNpcPacket), 2L);
        }, 2L);
    }

    public void remove() {
        seatEntity.setRunnable(null);
        HandlerList.unregisterAll(listener);

        for (Player nearbyPlayer : nearbyPlayers) removeViewerPlayer(nearbyPlayer);
        serverPlayer.connection.send(new ClientboundRemoveEntitiesPacket(hideNameEntity.getId()));

        if (!sleepingIgnoredCache) seatPlayer.setSleepingIgnored(false);
        if (!serverPlayer.getActiveEffectsMap().containsKey(MobEffects.INVISIBILITY)) serverPlayer.setInvisible(false);

        setEquipmentVisibility(true);

        serverPlayer.getEntityData().set(LEFT_SHOULDER_ACCESSOR, playerNpc.getEntityData().get(LEFT_SHOULDER_ACCESSOR));
        serverPlayer.getEntityData().set(RIGHT_SHOULDER_ACCESSOR, playerNpc.getEntityData().get(RIGHT_SHOULDER_ACCESSOR));
        serverPlayer.setGlowingTag(playerNpc.hasGlowingTag());
    }

    private void removeViewerPlayer(Player player) {
        send(player, removeNpcInfoPacket);
        send(player, removeNpcPacket);
        player.sendBlockChange(blockLocation, bedBlock.getBlockData());
    }

    private Set<Player> getNearbyPlayers() {
        Set<Player> result = new HashSet<>();
        for (Player player : seatPlayer.getWorld().getPlayers()) {
            if (seatLocation.distanceSquared(player.getLocation()) <= (double) renderRange * renderRange && player.canSee(seatPlayer)) {
                result.add(player);
            }
        }
        return result;
    }

    private static float fixYaw(float yaw) {
        return (yaw < 0f ? 360f + yaw : yaw) % 360f;
    }

    private void updateDirection() {
        float playerYaw = seatPlayer.getLocation().getYaw();
        if (directionCache == playerYaw) return;
        directionCache = playerYaw;

        if (direction == Direction.WEST) playerYaw -= 90;
        if (direction == Direction.EAST) playerYaw += 90;
        if (direction == Direction.NORTH) playerYaw -= 180;
        playerYaw = fixYaw(playerYaw);

        byte fixedRotation = getFixedRotation(playerYaw >= 315 ? playerYaw - 360 : playerYaw <= 45 ? playerYaw : playerYaw >= 180 ? -45 : 45);
        ClientboundRotateHeadPacket rotateHeadPacket = new ClientboundRotateHeadPacket(playerNpc, fixedRotation);
        for (Player nearbyPlayer : nearbyPlayers) send(nearbyPlayer, rotateHeadPacket);
    }

    private void updateSkin() {
        playerNpc.setInvisible(serverPlayer.getActiveEffectsMap().containsKey(MobEffects.INVISIBILITY));
        SynchedEntityData entityData = playerNpc.getEntityData();
        entityData.set(MAIN_HAND_ACCESSOR, serverPlayer.getEntityData().get(MAIN_HAND_ACCESSOR));
        entityData.set(SKIN_ACCESSOR, serverPlayer.getEntityData().get(SKIN_ACCESSOR));
        if (!entityData.isDirty()) return;
        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(playerNpc.getId(), entityData.packDirty());
        for (Player nearbyPlayer : nearbyPlayers) send(nearbyPlayer, packet);
    }

    private void updateEquipment() {
        ItemStack mainItemStack = serverPlayer.getItemBySlot(EquipmentSlot.MAINHAND);
        if (equipmentSlotCache != null && equipmentSlotCache.equals(serverPlayer.getInventory().getContents()) && mainSlotCache == mainItemStack) {
            return;
        }
        equipmentSlotCache = NonNullList.create();
        equipmentSlotCache.addAll(serverPlayer.getInventory().getContents());
        mainSlotCache = mainItemStack;

        List<Pair<EquipmentSlot, ItemStack>> equipmentList = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) equipmentList.add(Pair.of(slot, serverPlayer.getItemBySlot(slot)));
        ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(playerNpc.getId(), equipmentList);
        for (Player nearbyPlayer : nearbyPlayers) send(nearbyPlayer, packet);

        serverPlayer.containerMenu.sendAllDataToRemote();
    }

    private void setEquipmentVisibility(boolean visibility) {
        List<Pair<EquipmentSlot, ItemStack>> equipmentList = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack itemStack = visibility ? serverPlayer.getItemBySlot(slot) : null;
            equipmentList.add(Pair.of(slot, itemStack != null ? itemStack : ItemStack.EMPTY));
        }
        ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(serverPlayer.getId(), equipmentList);
        for (Player nearbyPlayer : nearbyPlayers) send(nearbyPlayer, packet);
    }

    private void playDamageAnimation() {
        ClientboundHurtAnimationPacket packet = new ClientboundHurtAnimationPacket(playerNpc);
        for (Player nearbyPlayer : nearbyPlayers) send(nearbyPlayer, packet);
    }

    private void playHandAnimation(InteractionHand hand) {
        ClientboundSwingAnimationPacket packet = new ClientboundSwingAnimationPacket(playerNpc, hand, SwingAnimation.DEFAULT);
        for (Player nearbyPlayer : nearbyPlayers) send(nearbyPlayer, packet);
    }

    private static byte getFixedRotation(float rotation) {
        return (byte) (rotation * 256f / 360f);
    }

    private Direction getDirection() {
        float yaw = seatLocation.getYaw();
        return (yaw >= 135f || yaw < -135f) ? Direction.NORTH : (yaw >= -135f && yaw < -45f) ? Direction.EAST
                : (yaw >= -45f && yaw < 45f) ? Direction.SOUTH : yaw >= 45f ? Direction.WEST : Direction.NORTH;
    }

    private ServerPlayer createNpc() {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) seatLocation.getWorld()).getHandle();
        GameProfile profile = new GameProfile(UUID.randomUUID(), seatPlayer.getName(), serverPlayer.getGameProfile().properties());
        ServerPlayer npc = new ServerPlayer(server, level, profile, serverPlayer.clientInformation());
        npc.connection = serverPlayer.connection;
        return npc;
    }

    private static void send(Player player, Packet<?> packet) {
        ((CraftPlayer) player).getHandle().connection.send(packet);
    }
}
