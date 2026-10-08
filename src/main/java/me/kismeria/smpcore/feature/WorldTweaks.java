package me.kismeria.smpcore.feature;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Мелочи мира из Vanilla Refresh: опыт летит к убийце, стойки с руками и позами, флаг на голову,
 * пиглины в броне с отделкой, TNT без разрушений, ускорение на тропинках, компас с местом смерти,
 * фейерверк на торте.
 */
public final class WorldTweaks implements Listener {

    private static final double[][] POSES = {
            // голова, тело, левая рука, правая рука, левая нога, правая нога (градусы x,y,z)
            {0, 0, 0, 0, 0, 0, -10, 0, -10, -15, 0, 10, -1, 0, -1, 1, 0, 1},
            {-15, 0, 0, 0, 0, 0, -110, 35, 0, -110, -35, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, 20, 0, -10, -120, 0, 10, 15, 0, 0, -15, 0, 0},
            {10, 0, 0, 5, 0, 0, -60, -30, 0, -60, 30, 0, -20, 0, 0, 20, 0, 0},
            {-20, 0, 0, 0, 0, 0, 0, 0, -120, 0, 0, 120, 0, 0, -10, 0, 0, 10},
            {25, 0, 0, 10, 0, 0, -40, 0, -10, -90, 0, 10, -45, 0, 0, 45, 0, 0},
            {0, 30, 0, 0, 0, 0, -90, 30, 0, -10, 0, 20, 0, 0, 0, 0, 0, 0},
    };
    private static final Color[] PARTY = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA,
            Color.BLUE, Color.FUCHSIA, Color.PURPLE, Color.WHITE};
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Material[] GOLD_ARMOR = {Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE,
            Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS};

    private static final Particle.DustTransition ORB_TRAIL =
            new Particle.DustTransition(Color.fromRGB(248, 255, 0), Color.fromRGB(7, 115, 255), 1f);

    private record Homing(ExperienceOrb orb, UUID target, long until) {
    }

    private final SmpCore plugin;
    private final NamespacedKey poseKey;
    private final NamespacedKey confettiKey;
    private final NamespacedKey pathKey;
    private final List<Homing> homing = new java.util.ArrayList<>();
    private final Map<UUID, Deque<Long>> sneaks = new HashMap<>();
    private final Set<UUID> onPath = new HashSet<>();
    private int ticks;

    public WorldTweaks(SmpCore plugin) {
        this.plugin = plugin;
        this.poseKey = new NamespacedKey(plugin, "pose");
        this.confettiKey = new NamespacedKey(plugin, "confetti");
        this.pathKey = new NamespacedKey(plugin, "path_sprint");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        ticks++;
        tickHoming();
        if (ticks % 5 == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                tickPath(player);
            }
        }
        if (ticks % 20 == 0 && plugin.flag("features.compass-death")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                tickCompass(player);
            }
        }
    }

    // ---------------------------------------------------------------- опыт летит к убийце

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent event) {
        if (event instanceof PlayerDeathEvent || !plugin.flag("features.homing-xp")) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        int exp = event.getDroppedExp();
        if (killer == null || exp <= 0) {
            return;
        }
        event.setDroppedExp(0);
        Location at = event.getEntity().getLocation().add(0, 0.5, 0);
        ExperienceOrb orb = at.getWorld().spawn(at, ExperienceOrb.class, spawned -> {
            spawned.setExperience(exp);
            spawned.setGravity(false);
            spawned.setInvulnerable(true);
        });
        homing.add(new Homing(orb, killer.getUniqueId(), System.currentTimeMillis() + 10_000));
    }

    private void tickHoming() {
        long now = System.currentTimeMillis();
        homing.removeIf(h -> {
            if (!h.orb().isValid()) {
                return true;
            }
            Player player = Bukkit.getPlayer(h.target());
            if (player == null || player.isDead() || player.getWorld() != h.orb().getWorld() || now > h.until()) {
                h.orb().setGravity(true);
                h.orb().setInvulnerable(false);
                return true;
            }
            Vector to = player.getLocation().add(0, 0.9, 0).toVector().subtract(h.orb().getLocation().toVector());
            double distance = to.length();
            if (distance > 0.3) {
                h.orb().setVelocity(to.normalize().multiply(Math.min(0.9, 0.2 + distance * 0.06)));
            }
            // след как в Vanilla Refresh: жёлтый → голубой
            if (ticks % 2 == 0) {
                h.orb().getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, h.orb().getLocation(), 1, 0, 0, 0, 0, ORB_TRAIL);
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- стойки для брони

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(EntityPlaceEvent event) {
        if (plugin.flag("features.armor-stands") && event.getEntity() instanceof ArmorStand stand) {
            stand.setArms(true);
        }
    }

    /** Шифт + ПКМ пустой рукой — следующая поза. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPose(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof ArmorStand stand) || event.getHand() != EquipmentSlot.HAND
                || !plugin.flag("features.armor-stands")) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.getInventory().getItemInMainHand().getType().isAir() || stand.isMarker()) {
            return;
        }
        event.setCancelled(true);
        int next = (stand.getPersistentDataContainer().getOrDefault(poseKey, PersistentDataType.INTEGER, 0) + 1) % POSES.length;
        stand.getPersistentDataContainer().set(poseKey, PersistentDataType.INTEGER, next);
        double[] p = POSES[next];
        stand.setArms(true);
        stand.setHeadPose(angle(p, 0));
        stand.setBodyPose(angle(p, 3));
        stand.setLeftArmPose(angle(p, 6));
        stand.setRightArmPose(angle(p, 9));
        stand.setLeftLegPose(angle(p, 12));
        stand.setRightLegPose(angle(p, 15));
        // как в Vanilla Refresh: стук дерева и щепки
        Location chest = stand.getLocation().add(0, 0.75, 0);
        stand.getWorld().playSound(chest, Sound.BLOCK_WOOD_PLACE, 0.8f, 1.25f);
        stand.getWorld().spawnParticle(Particle.BLOCK, chest, 8, 0.2, 0.4, 0.2, 0, Material.OAK_PLANKS.createBlockData());
    }

    private static EulerAngle angle(double[] p, int from) {
        return new EulerAngle(Math.toRadians(p[from]), Math.toRadians(p[from + 1]), Math.toRadians(p[from + 2]));
    }

    // ---------------------------------------------------------------- флаг на голову

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking() || !plugin.flag("features.banner-hats")) {
            return;
        }
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Deque<Long> times = sneaks.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > 1500) {
            times.removeFirst();
        }
        if (times.size() < 3) {
            return;
        }
        times.clear();
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        if (!Tag.BANNERS.isTagged(hand.getType())) {
            return;
        }
        ItemStack helmet = inventory.getHelmet();
        if (helmet != null && helmet.containsEnchantment(Enchantment.BINDING_CURSE)) {
            return;
        }
        ItemStack banner = hand.asOne();
        hand.setAmount(hand.getAmount() - 1);
        inventory.setItemInMainHand(hand.getAmount() > 0 ? hand : null);
        inventory.setHelmet(banner);
        if (helmet != null && !helmet.getType().isAir()) {
            for (ItemStack left : inventory.addItem(helmet).values()) {
                player.getWorld().dropItem(player.getLocation(), left);
            }
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 0.6f);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 0.9f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getEyeLocation().add(0, 0.4, 0), 4, 0.15, 0.05, 0.15, 0.01);
    }

    // ---------------------------------------------------------------- пиглины с отделкой

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Piglin) && !(event.getEntity() instanceof PiglinBrute)) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL
                && reason != CreatureSpawnEvent.SpawnReason.SPAWNER && reason != CreatureSpawnEvent.SpawnReason.DEFAULT) {
            return;
        }
        double chance = plugin.getConfig().getDouble("features.trimmed-piglins-chance", 15);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble(100) >= chance) {
            return;
        }
        List<TrimPattern> patterns = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).stream().toList();
        List<TrimMaterial> materials = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).stream().toList();
        if (patterns.isEmpty() || materials.isEmpty()) {
            return;
        }
        EntityEquipment equipment = event.getEntity().getEquipment();
        if (equipment == null) {
            return;
        }
        // один узор и материал на весь комплект — смотрится как задумано
        ArmorTrim trim = new ArmorTrim(materials.get(random.nextInt(materials.size())), patterns.get(random.nextInt(patterns.size())));
        boolean any = false;
        for (int i = 0; i < ARMOR.length; i++) {
            if (random.nextBoolean() && (i < ARMOR.length - 1 || any)) {
                continue;
            }
            any = true;
            ItemStack piece = new ItemStack(GOLD_ARMOR[i]);
            piece.editMeta(ArmorMeta.class, meta -> meta.setTrim(trim));
            equipment.setItem(ARMOR[i], piece);
            equipment.setDropChance(ARMOR[i], 0.05f);
        }
    }

    // ---------------------------------------------------------------- TNT без разрушений

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Entity source = event.getEntity();
        if ((source instanceof TNTPrimed || source instanceof ExplosiveMinecart) && !plugin.flag("world.tnt-block-damage")) {
            event.blockList().clear();
        }
    }

    // ---------------------------------------------------------------- тропинки

    private void tickPath(Player player) {
        boolean wanted = plugin.flag("features.path-sprint") && player.isOnGround()
                && player.getGameMode() != GameMode.SPECTATOR && !player.isInsideVehicle()
                && player.getLocation().subtract(0, 0.1, 0).getBlock().getType() == Material.DIRT_PATH;
        boolean has = onPath.contains(player.getUniqueId());
        if (wanted == has) {
            return;
        }
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(pathKey);
        if (wanted) {
            double bonus = plugin.getConfig().getDouble("features.path-sprint-bonus", 20) / 100.0;
            speed.addTransientModifier(new AttributeModifier(pathKey, bonus, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            onPath.add(player.getUniqueId());
        } else {
            onPath.remove(player.getUniqueId());
        }
    }

    // ---------------------------------------------------------------- компас с местом смерти

    private void tickCompass(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (!plainCompass(main) && !plainCompass(off)) {
            return;
        }
        Location death = player.getLastDeathLocation();
        if (death == null || death.getWorld() == null) {
            plugin.lang().actionBar(player, "compass.no-death");
            return;
        }
        plugin.lang().actionBar(player, "compass.death",
                Lang.ph("x", death.getBlockX()), Lang.ph("y", death.getBlockY()), Lang.ph("z", death.getBlockZ()),
                Lang.txt("world", worldName(player, death.getWorld())));
    }

    /** Обычный компас (не привязанный к магнетиту). */
    private static boolean plainCompass(ItemStack item) {
        return item.getType() == Material.COMPASS && !(item.getItemMeta() instanceof CompassMeta meta && meta.hasLodestone());
    }

    public String worldName(Player viewer, World world) {
        return switch (world.getEnvironment()) {
            case NORMAL -> plugin.lang().raw(viewer, "dimension-name.overworld");
            case NETHER -> plugin.lang().raw(viewer, "dimension-name.nether");
            case THE_END -> plugin.lang().raw(viewer, "dimension-name.end");
            default -> world.getName();
        };
    }

    // ---------------------------------------------------------------- фейерверк на торте

    @EventHandler(priority = EventPriority.HIGH)
    public void onCake(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || event.getHand() != EquipmentSlot.HAND
                || !plugin.flag("features.party-cake")) {
            return;
        }
        if (block.getType() != Material.CAKE && !Tag.CANDLE_CAKES.isTagged(block.getType())) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack rocket = player.getInventory().getItemInMainHand();
        if (rocket.getType() != Material.FIREWORK_ROCKET) {
            return;
        }
        event.setCancelled(true);
        FireworkMeta source = rocket.getItemMeta() instanceof FireworkMeta meta ? meta : null;
        if (player.getGameMode() != GameMode.CREATIVE) {
            rocket.setAmount(rocket.getAmount() - 1);
        }
        Location at = block.getLocation().add(0.5, 0.9, 0.5);
        Firework firework = at.getWorld().spawn(at, Firework.class, spawned -> {
            FireworkMeta meta = spawned.getFireworkMeta();
            if (source != null && source.hasEffects()) {
                meta.addEffects(source.getEffects());
            } else {
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BURST)
                        .withColor(party(), party(), party()).withFade(party()).flicker(true).build());
            }
            meta.setPower(0);
            spawned.setFireworkMeta(meta);
            spawned.getPersistentDataContainer().set(confettiKey, PersistentDataType.BOOLEAN, true);
        });
        firework.detonate();
        at.getWorld().playSound(at, Sound.ENTITY_VILLAGER_CELEBRATE, 0.8f, 1.2f);
        // конфетти сыплется ещё пару секунд (в Vanilla Refresh — искры фейерверка над тортом)
        new org.bukkit.scheduler.BukkitRunnable() {
            int t;

            @Override
            public void run() {
                Location top = at.clone().add(0, 1.6, 0);
                for (int i = 0; i < 3; i++) {
                    top.getWorld().spawnParticle(Particle.DUST, top, 1, 1.2, 0.3, 1.2, 0,
                            new Particle.DustOptions(party(), 0.9f));
                }
                if (t % 4 == 0) {
                    top.getWorld().spawnParticle(Particle.FIREWORK, top, 1, 0.6, 0.2, 0.6, 0.02);
                }
                if (++t >= 40) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 2L, 1L);
    }

    /** Конфетти с торта никого не ранит. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConfettiDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager().getType() == EntityType.FIREWORK_ROCKET
                && event.getDamager().getPersistentDataContainer().has(confettiKey, PersistentDataType.BOOLEAN)) {
            event.setCancelled(true);
        }
    }

    private static Color party() {
        return PARTY[ThreadLocalRandom.current().nextInt(PARTY.length)];
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sneaks.remove(id);
        if (onPath.remove(id)) {
            AttributeInstance speed = event.getPlayer().getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                speed.removeModifier(pathKey);
            }
        }
    }
}
