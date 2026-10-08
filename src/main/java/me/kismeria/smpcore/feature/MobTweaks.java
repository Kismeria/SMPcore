package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Biome;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Bogged;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.PolarBear;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Salmon;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.SkeletonHorse;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Turtle;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.ZombieHorse;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Порт части Mob Tweaks (Inkorthe2nd): маленькие гасты, детёныши криперов/пауков/скелетов,
 * новые наездники, мобы сами садятся на соседей, баффы/нерфы атрибутов и доп. дроп.
 * Метка в PDC переживает рестарт: атрибуты и детёнышей не накатываем дважды.
 */
public final class MobTweaks implements Listener {

    private static final String SEEN = "seen";
    private static final String BABY = "baby";
    private static final String GHASTLING = "ghastling";

    /** Откуда спавн считается «природным» для детёнышей и наездников (команды и плагины не трогаем). */
    private static final Set<SpawnReason> NATURAL = Set.of(SpawnReason.NATURAL, SpawnReason.SPAWNER,
            SpawnReason.SPAWNER_EGG, SpawnReason.TRIAL_SPAWNER, SpawnReason.REINFORCEMENTS, SpawnReason.PATROL,
            SpawnReason.JOCKEY, SpawnReason.MOUNT, SpawnReason.DEFAULT);

    private final SmpCore plugin;
    private final NamespacedKey key;
    /** Немые детёныши: свои звуки с высоким питчем. */
    private final Set<UUID> voiced = new HashSet<>();

    public MobTweaks(SmpCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "mob_tweak");
        Bukkit.getScheduler().runTaskTimer(plugin, this::second, 40L, 20L);
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                loaded(entity);
            }
        }
    }

    // ---------------------------------------------------------------- спавн

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        boolean natural = NATURAL.contains(event.getSpawnReason());
        // через тик: у ванильных жокеев к этому моменту уже есть наездник
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (entity.isValid() && !entity.getPersistentDataContainer().has(key)) {
                process(entity, natural);
            }
        });
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living) {
                loaded(living);
            }
        }
    }

    /** Старые мобы из чанка: только атрибуты, детёнышами задним числом не делаем. */
    private void loaded(LivingEntity entity) {
        String tag = tag(entity);
        if (tag == null) {
            if (plugin.flag("mobs.attributes") && attributes(entity)) {
                mark(entity, SEEN);
            }
        } else if (!tag.equals(SEEN) && entity.isSilent()) {
            voiced.add(entity.getUniqueId());
        }
    }

    private void process(LivingEntity entity, boolean natural) {
        if (plugin.flag("mobs.attributes")) {
            attributes(entity);
        }
        String tag = SEEN;
        if (natural) {
            if (babyRoll(entity)) {
                tag = entity instanceof Ghast ? GHASTLING : BABY;
            } else if (plugin.flag("mobs.jockeys")) {
                spawnJockey(entity);
            }
        }
        if (tag(entity) == null) {
            mark(entity, tag);
        }
    }

    /** Скелет сгорел в снегу и стал страем — детёныш остаётся детёнышем. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (!BABY.equals(tag(event.getEntity())) || !(event.getTransformedEntity() instanceof LivingEntity result)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (result.isValid()) {
                attributes(result);
                makeBabySkeleton(result);
            }
        });
    }

    // ---------------------------------------------------------------- атрибуты

    /** Баффы и нерфы из Mob Tweaks 1.1. true — моб из тех, кого правим. */
    private static boolean attributes(LivingEntity entity) {
        switch (entity) {
            case CaveSpider ignored -> {
                base(entity, Attribute.MAX_HEALTH, 6);
                base(entity, Attribute.ATTACK_DAMAGE, 1);
                base(entity, Attribute.MOVEMENT_SPEED, 0.2);
                base(entity, Attribute.STEP_HEIGHT, 0.3);
                heal(entity);
            }
            case Spider ignored -> base(entity, Attribute.STEP_HEIGHT, 1.2);
            case Ravager ignored -> base(entity, Attribute.STEP_HEIGHT, 1.5);
            case Blaze ignored -> {
                base(entity, Attribute.FOLLOW_RANGE, 40);
                base(entity, Attribute.ATTACK_DAMAGE, 4);
            }
            case Ghast ignored -> base(entity, Attribute.FOLLOW_RANGE, 90);
            case Stray ignored -> {
                base(entity, Attribute.MAX_HEALTH, 16);
                base(entity, Attribute.FOLLOW_RANGE, 12);
                base(entity, Attribute.MOVEMENT_SPEED, 0.22);
                base(entity, Attribute.BURNING_TIME, 2);
                heal(entity);
            }
            case Husk husk -> {
                if (!husk.isAdult()) {
                    return false;
                }
                base(entity, Attribute.ATTACK_DAMAGE, 2);
                base(entity, Attribute.ARMOR, 1);
                base(entity, Attribute.MOVEMENT_SPEED, 0.2);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void base(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** Хп не выше нового максимума; только что заспавненный — полный. */
    private static void heal(LivingEntity entity) {
        AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) {
            entity.setHealth(entity.getTicksLived() < 5 ? max.getValue() : Math.min(entity.getHealth(), max.getValue()));
        }
    }

    // ---------------------------------------------------------------- детёныши

    private boolean babyRoll(LivingEntity entity) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (entity instanceof Ghast ghast) {
            if (!plugin.flag("mobs.sad-ghastlings") || random.nextDouble() >= 0.10) {
                return false;
            }
            makeGhastling(ghast);
            return true;
        }
        if (!plugin.flag("mobs.babies")) {
            return false;
        }
        return switch (entity) {
            case Creeper creeper when random.nextDouble() < 0.05 -> {
                makeBabyCreeper(creeper);
                yield true;
            }
            case Spider spider when !(spider instanceof CaveSpider) && spider.getPassengers().isEmpty()
                    && random.nextDouble() < 0.10 -> {
                makeBabySpider(spider);
                yield true;
            }
            case LivingEntity skeleton when isSkeleton(skeleton) && !horseman(skeleton) && random.nextDouble() < 0.10 -> {
                yield makeBabySkeleton(skeleton);
            }
            default -> false;
        };
    }

    private static boolean isSkeleton(Entity entity) {
        return entity instanceof Skeleton || entity instanceof Stray || entity instanceof Bogged
                || entity instanceof WitherSkeleton;
    }

    /** Всадники ловушки-молнии (железный шлем на скелетной лошади) остаются взрослыми. */
    private static boolean horseman(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        return entity.getVehicle() instanceof SkeletonHorse
                || equipment != null && equipment.getHelmet().getType() == Material.IRON_HELMET;
    }

    private void makeGhastling(Ghast ghast) {
        base(ghast, Attribute.MAX_HEALTH, 2.5);
        base(ghast, Attribute.FOLLOW_RANGE, 80);
        base(ghast, Attribute.SCALE, 0.3);
        base(ghast, Attribute.FLYING_SPEED, 0.01);
        ghast.setExplosionPower(0);
        ghast.setHealth(2.5);
        voice(ghast);
        mark(ghast, GHASTLING);
    }

    private void makeBabyCreeper(Creeper creeper) {
        base(creeper, Attribute.MAX_HEALTH, 5);
        base(creeper, Attribute.SCALE, 0.6);
        base(creeper, Attribute.MOVEMENT_SPEED, 0.35);
        creeper.setHealth(5);
        creeper.setExplosionRadius(1);
        // звуки у крипера свои (ванильные), немым не делаем — иначе не слышно шипения
        mark(creeper, BABY);
    }

    private void makeBabySpider(Spider spider) {
        base(spider, Attribute.MAX_HEALTH, 4);
        base(spider, Attribute.FOLLOW_RANGE, 8);
        base(spider, Attribute.ATTACK_DAMAGE, 0.5);
        base(spider, Attribute.SCALE, 0.4);
        base(spider, Attribute.MOVEMENT_SPEED, 0.5);
        base(spider, Attribute.STEP_HEIGHT, 0.3);
        spider.setHealth(4);
        voice(spider);
        mark(spider, BABY);
    }

    /** Скелет/страй/болотник/визер-скелет ростом 0.6 со стрелой или палкой вместо оружия. */
    private boolean makeBabySkeleton(LivingEntity skeleton) {
        if (!isSkeleton(skeleton)) {
            return false;
        }
        double health = skeleton instanceof Stray ? 4 : 5;
        base(skeleton, Attribute.MAX_HEALTH, health);
        base(skeleton, Attribute.SCALE, 0.6);
        base(skeleton, Attribute.MOVEMENT_SPEED, 0.28);
        skeleton.setHealth(health);
        EntityEquipment equipment = skeleton.getEquipment();
        if (equipment != null) {
            ItemStack hand = switch (skeleton) {
                case Stray ignored -> tipped(PotionType.SLOWNESS);
                case Bogged ignored -> tipped(PotionType.POISON);
                case WitherSkeleton ignored -> new ItemStack(Material.STICK);
                default -> new ItemStack(Material.ARROW);
            };
            equipment.setItemInMainHand(hand);
            equipment.setItemInMainHandDropChance(0f);
        }
        voice(skeleton);
        mark(skeleton, BABY);
        return true;
    }

    private static ItemStack tipped(PotionType type) {
        ItemStack arrow = new ItemStack(Material.TIPPED_ARROW);
        arrow.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(type));
        return arrow;
    }

    private void voice(LivingEntity entity) {
        entity.setSilent(true);
        voiced.add(entity.getUniqueId());
    }

    // ---------------------------------------------------------------- наездники при спавне

    private void spawnJockey(LivingEntity entity) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!entity.getPassengers().isEmpty() && !(entity instanceof Chicken)) {
            return;
        }
        switch (entity) {
            case CaveSpider spider -> {
                if (random.nextDouble() < 0.10) {
                    rider(spider, EntityType.SPIDER, rider -> makeBabySpider((Spider) rider));
                }
            }
            case Spider spider -> {
                if (random.nextDouble() < 0.10) {
                    rider(spider, EntityType.SPIDER, rider -> makeBabySpider((Spider) rider));
                } else if (random.nextDouble() < 0.05) {
                    rider(spider, inCave(spider.getLocation()) ? EntityType.CAVE_SPIDER : EntityType.CREEPER, null);
                }
            }
            case Blaze blaze -> {
                if (random.nextDouble() < 0.10) {
                    rider(blaze, EntityType.SKELETON, null);
                }
            }
            case Chicken chicken -> chickenJockey(chicken);
            default -> {
            }
        }
    }

    /** Посадить на моба нового наездника; он проходит те же правки (атрибуты, шанс стать детёнышем). */
    private void rider(LivingEntity vehicle, EntityType type, java.util.function.Consumer<LivingEntity> setup) {
        Entity spawned = vehicle.getWorld().spawnEntity(vehicle.getLocation(), type, SpawnReason.JOCKEY);
        if (!(spawned instanceof LivingEntity rider)) {
            return;
        }
        if (plugin.flag("mobs.attributes")) {
            attributes(rider);
        }
        if (setup != null) {
            setup.accept(rider);
        } else if (!babyRoll(rider)) {
            mark(rider, SEEN);
        }
        vehicle.addPassenger(rider);
    }

    /**
     * Куриный жокей: под водой малыш-утопленник едет на большом лососе, в пещере малыш-зомби —
     * на пещерном пауке, в пещере на болоте вместо всей пары — малыш-болотник на пещерном пауке.
     */
    private void chickenJockey(Chicken chicken) {
        if (!chicken.isChickenJockey()) {
            return;
        }
        Zombie baby = null;
        for (Entity passenger : chicken.getPassengers()) {
            if (passenger instanceof Zombie zombie && !zombie.isAdult()) {
                baby = zombie;
            }
        }
        if (baby == null) {
            return;
        }
        Location location = chicken.getLocation();
        World world = chicken.getWorld();
        if (baby instanceof Drowned && chicken.isInWater()) {
            Salmon salmon = world.spawn(location, Salmon.class, SpawnReason.JOCKEY, s -> s.setVariant(Salmon.Variant.LARGE));
            mark(salmon, SEEN);
            swapVehicle(chicken, baby, salmon);
            return;
        }
        if (baby.getType() != EntityType.ZOMBIE || !inCave(location) || location.getBlock().getLightFromSky() > 0) {
            return;
        }
        if (swamp(world.getBiome(location))) {
            baby.remove();
            chicken.remove();
            CaveSpider spider = world.spawn(location, CaveSpider.class, SpawnReason.JOCKEY, s -> {
                attributes(s);
                mark(s, SEEN);
            });
            Bogged bogged = world.spawn(location, Bogged.class, SpawnReason.JOCKEY, this::makeBabySkeleton);
            spider.addPassenger(bogged);
            return;
        }
        CaveSpider spider = world.spawn(location, CaveSpider.class, SpawnReason.JOCKEY, s -> {
            attributes(s);
            mark(s, SEEN);
        });
        swapVehicle(chicken, baby, spider);
    }

    private static void swapVehicle(Entity old, Entity rider, Entity vehicle) {
        rider.leaveVehicle();
        old.remove();
        vehicle.addPassenger(rider);
    }

    private static boolean swamp(Biome biome) {
        return biome == Biome.SWAMP || biome == Biome.MANGROVE_SWAMP;
    }

    /** Как in_cave из датапака: ниже уровня моря, в cave_air или в пещерном биоме. */
    private static boolean inCave(Location location) {
        if (location.getBlockY() <= 62 || location.getBlock().getType() == Material.CAVE_AIR) {
            return true;
        }
        Biome biome = location.getWorld().getBiome(location);
        return biome == Biome.LUSH_CAVES || biome == Biome.DRIPSTONE_CAVES || biome == Biome.DEEP_DARK;
    }

    // ---------------------------------------------------------------- каждую секунду

    private void second() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // голоса детёнышей
        Iterator<UUID> iterator = voiced.iterator();
        while (iterator.hasNext()) {
            Entity entity = Bukkit.getEntity(iterator.next());
            if (!(entity instanceof LivingEntity living) || !living.isValid()) {
                iterator.remove();
                continue;
            }
            double chance = living instanceof Ghast ? 0.05 : 0.10;
            if (random.nextDouble() < chance && !nearbyPlayers(living, 16).isEmpty()) {
                voice(living, Kind.AMBIENT);
            }
        }
        if (plugin.flag("mobs.jockeys")) {
            mountNearby(random);
        }
    }

    private static List<Player> nearbyPlayers(Entity entity, double radius) {
        List<Player> list = new ArrayList<>();
        Location location = entity.getLocation();
        for (Player player : entity.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= radius * radius) {
                list.add(player);
            }
        }
        return list;
    }

    /** 10% в секунду: моб без транспорта садится на подходящего соседа без наездника в 2 блоках (на разорителя — 3). */
    private void mountNearby(ThreadLocalRandom random) {
        for (World world : Bukkit.getWorlds()) {
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) {
                continue;
            }
            for (LivingEntity entity : world.getLivingEntities()) {
                if (!(entity instanceof Mob mob) || !mob.hasAI() || mob.isInsideVehicle() || mob.isLeashed()
                        || random.nextDouble() >= 0.10 || !nearAnyPlayer(mob, players)) {
                    continue;
                }
                tryMount(mob);
            }
        }
    }

    private static boolean nearAnyPlayer(Entity entity, List<Player> players) {
        Location location = entity.getLocation();
        for (Player player : players) {
            if (player.getLocation().distanceSquared(location) <= 128 * 128) {
                return true;
            }
        }
        return false;
    }

    private void tryMount(Mob mob) {
        boolean baby = BABY.equals(tag(mob)) || mob instanceof Ageable ageable && !ageable.isAdult();
        switch (mob) {
            case Creeper ignored -> {
                if (baby) {
                    mount(mob, 2, e -> e instanceof CaveSpider);
                } else {
                    mount(mob, 2, this::adultSpider);
                }
            }
            case CaveSpider ignored -> mount(mob, 2, this::adultSpider);
            case Spider ignored -> {
                if (baby) {
                    mount(mob, 2, e -> adultSpider(e) || e instanceof CaveSpider);
                }
            }
            case WitherSkeleton ignored -> mount(mob, 2, e -> e instanceof Hoglin hoglin && hoglin.isAdult());
            case LivingEntity skeleton when isSkeleton(skeleton) -> {
                boolean done = mount(mob, 2, e -> adultSpider(e) || farmAnimal(e)
                        || !(skeleton instanceof Bogged) && e instanceof Goat goat && goat.isAdult()
                        || e instanceof SkeletonHorse horse && horse.isAdult());
                if (!done && baby) {
                    done = mount(mob, 2, e -> e instanceof CaveSpider
                            || e instanceof SkeletonHorse horse && !horse.isAdult());
                }
                if (!done && skeleton instanceof Skeleton) {
                    done = mount(mob, 2, e -> e instanceof Blaze || e instanceof Hoglin hoglin && hoglin.isAdult());
                }
                if (!done && skeleton instanceof Stray) {
                    mount(mob, 2, e -> e instanceof PolarBear bear && bear.isAdult());
                }
            }
            case Drowned drowned -> {
                boolean done = mount(mob, 2, e -> e instanceof Turtle turtle && turtle.isAdult()
                        || e instanceof SkeletonHorse horse && horse.isAdult());
                if (!done && baby) {
                    done = mount(mob, 2, e -> e instanceof SkeletonHorse horse && !horse.isAdult()
                            || e instanceof Salmon salmon && salmon.getVariant() == Salmon.Variant.LARGE);
                }
                if (!done && baby) {
                    mount(mob, 2, e -> zombieKind(e) && ((Zombie) e).isAdult());
                }
            }
            case Zombie zombie when zombie instanceof Husk || zombie instanceof ZombieVillager
                    || zombie.getType() == EntityType.ZOMBIE -> {
                boolean done = mount(mob, 2, e -> adultSpider(e) || farmAnimal(e)
                        || e instanceof ZombieHorse horse && horse.isAdult());
                if (!done && baby) {
                    done = mount(mob, 2, e -> e instanceof CaveSpider
                            || e instanceof Chicken chicken && chicken.isAdult()
                            || e instanceof ZombieHorse horse && !horse.isAdult());
                }
                if (!done && baby) {
                    mount(mob, 2, e -> zombieKind(e) && ((Zombie) e).isAdult());
                }
            }
            case LivingEntity illager when illager.getType() == EntityType.WITCH || illager.getType() == EntityType.PILLAGER
                    || illager.getType() == EntityType.VINDICATOR || illager.getType() == EntityType.EVOKER
                    || illager.getType() == EntityType.ILLUSIONER -> {
                if (illager.getType() == EntityType.WITCH && mount(mob, 2, this::adultSpider)) {
                    return;
                }
                mount(mob, 3, e -> e instanceof Ravager);
            }
            case LivingEntity piglin when piglin.getType() == EntityType.PIGLIN
                    || piglin.getType() == EntityType.PIGLIN_BRUTE -> {
                if (piglin instanceof Ageable ageable && ageable.isAdult()) {
                    mount(mob, 2, e -> e instanceof Hoglin hoglin && hoglin.isAdult());
                }
            }
            default -> {
            }
        }
    }

    private boolean adultSpider(Entity entity) {
        return entity.getType() == EntityType.SPIDER && !BABY.equals(tag(entity));
    }

    private static boolean farmAnimal(Entity entity) {
        return (entity instanceof Cow || entity instanceof Sheep || entity instanceof Pig)
                && ((Ageable) entity).isAdult();
    }

    private static boolean zombieKind(Entity entity) {
        return entity instanceof Zombie zombie && (zombie.getType() == EntityType.ZOMBIE
                || zombie instanceof Husk || zombie instanceof ZombieVillager || zombie instanceof Drowned);
    }

    /** Ближайший подходящий транспорт без наездников. Чужих питомцев, привязанных и с именем не берём. */
    private static boolean mount(Mob mob, double radius, Predicate<Entity> fits) {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mob.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity vehicle) || entity instanceof Player || !vehicle.getPassengers().isEmpty()
                    || vehicle.isInsideVehicle() || vehicle.customName() != null
                    || vehicle instanceof Mob m && m.isLeashed()
                    || vehicle instanceof AbstractHorse horse && horse.isTamed()
                    || !fits.test(entity)) {
                continue;
            }
            double distance = entity.getLocation().distanceSquared(mob.getLocation());
            if (distance <= radius * radius && distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best != null && best.addPassenger(mob);
    }

    // ---------------------------------------------------------------- звуки детёнышей

    private enum Kind { AMBIENT, HURT, DEATH }

    private static void voice(LivingEntity entity, Kind kind) {
        Sound sound = switch (entity) {
            case Ghast ignored -> pick(kind, Sound.ENTITY_GHAST_AMBIENT, Sound.ENTITY_GHAST_HURT, Sound.ENTITY_GHAST_DEATH);
            case Spider ignored -> pick(kind, Sound.ENTITY_SPIDER_AMBIENT, Sound.ENTITY_SPIDER_HURT, Sound.ENTITY_SPIDER_DEATH);
            case Stray ignored -> pick(kind, Sound.ENTITY_STRAY_AMBIENT, Sound.ENTITY_STRAY_HURT, Sound.ENTITY_STRAY_DEATH);
            case Bogged ignored -> pick(kind, Sound.ENTITY_BOGGED_AMBIENT, Sound.ENTITY_BOGGED_HURT, Sound.ENTITY_BOGGED_DEATH);
            case WitherSkeleton ignored -> pick(kind, Sound.ENTITY_WITHER_SKELETON_AMBIENT,
                    Sound.ENTITY_WITHER_SKELETON_HURT, Sound.ENTITY_WITHER_SKELETON_DEATH);
            case Skeleton ignored -> pick(kind, Sound.ENTITY_SKELETON_AMBIENT, Sound.ENTITY_SKELETON_HURT,
                    Sound.ENTITY_SKELETON_DEATH);
            default -> null;
        };
        if (sound == null) {
            return;
        }
        float volume = entity instanceof Ghast ? (kind == Kind.AMBIENT ? 1.2f : 1.6f) : (kind == Kind.AMBIENT ? 0.5f : 0.8f);
        entity.getWorld().playSound(entity, sound, SoundCategory.HOSTILE, volume, 1.5f);
    }

    private static Sound pick(Kind kind, Sound ambient, Sound hurt, Sound death) {
        return switch (kind) {
            case AMBIENT -> ambient;
            case HURT -> hurt;
            case DEATH -> death;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof LivingEntity living && living.isSilent() && voiced.contains(living.getUniqueId())
                && event.getFinalDamage() < living.getHealth()) {
            voice(living, Kind.HURT);
        }
    }

    // ---------------------------------------------------------------- маленький гаст стреляет шариками ифрита

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof LargeFireball fireball) || !(fireball.getShooter() instanceof Ghast ghast)
                || !GHASTLING.equals(tag(ghast))) {
            return;
        }
        event.setCancelled(true);
        ghast.getWorld().spawn(fireball.getLocation(), SmallFireball.class, small -> {
            small.setShooter(ghast);
            small.setAcceleration(fireball.getAcceleration());
            small.setVelocity(fireball.getVelocity());
        });
        ghast.getWorld().playSound(ghast, Sound.ENTITY_GHAST_WARN, SoundCategory.HOSTILE, 1.2f, 1.5f);
        ghast.getWorld().playSound(ghast, Sound.ENTITY_GHAST_SHOOT, SoundCategory.HOSTILE, 1.2f, 1.5f);
    }

    // ---------------------------------------------------------------- дроп

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        String tag = tag(entity);
        Player killer = entity.getKiller();
        int looting = killer == null ? 0 : killer.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.LOOTING);
        List<ItemStack> drops = event.getDrops();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (BABY.equals(tag) || GHASTLING.equals(tag)) {
            if (voiced.remove(entity.getUniqueId())) {
                voice(entity, Kind.DEATH);
            }
            switch (entity) {
                case Ghast ignored -> {
                    drops.removeIf(item -> item.getType() == Material.GHAST_TEAR || item.getType() == Material.GUNPOWDER);
                    add(drops, Material.GHAST_TEAR, random.nextInt(2) + bonus(random, looting));
                }
                case Creeper ignored -> {
                    drops.removeIf(item -> item.getType() == Material.GUNPOWDER);
                    add(drops, Material.GUNPOWDER, random.nextInt(2) + bonus(random, looting));
                }
                case Spider ignored -> {
                    drops.removeIf(item -> item.getType() == Material.STRING || item.getType() == Material.SPIDER_EYE);
                    if (killer != null && random.nextInt(3) == 0) {
                        add(drops, Material.SPIDER_EYE, 1 + bonus(random, looting));
                    }
                }
                default -> {
                    // детёныши скелетов: костная мука вместо костей, без стрел и угля
                    drops.removeIf(item -> item.getType() == Material.BONE || item.getType() == Material.ARROW
                            || item.getType() == Material.TIPPED_ARROW || item.getType() == Material.COAL
                            || item.getType() == Material.WITHER_SKELETON_SKULL);
                    add(drops, Material.BONE_MEAL, random.nextInt(3) + bonus(random, looting));
                }
            }
            return;
        }

        if (killer == null || !plugin.flag("mobs.extra-drops")) {
            return;
        }
        Material extra = switch (entity) {
            case Creeper ignored -> Material.TNT;
            case CaveSpider ignored -> null;
            case Spider ignored -> Material.COBWEB;
            case Husk ignored -> Material.SAND;
            case Stray ignored -> Material.ICE;
            case Bogged ignored -> Material.VINE;
            case Ghast ignored -> Material.FIRE_CHARGE;
            case Phantom ignored -> Material.ROTTEN_FLESH;
            default -> null;
        };
        // шанс как у золотого слитка с зомби-пиглина: 2.5%, с добычей 3.5% + 1% за уровень сверх первого
        double chance = looting == 0 ? 0.025 : 0.035 + 0.01 * (looting - 1);
        if (extra != null && random.nextDouble() < chance) {
            drops.add(new ItemStack(extra));
        }
    }

    /** Добыча: +0..1 за каждый уровень. */
    private static int bonus(ThreadLocalRandom random, int looting) {
        int bonus = 0;
        for (int i = 0; i < looting; i++) {
            bonus += random.nextInt(2);
        }
        return bonus;
    }

    private static void add(List<ItemStack> drops, Material material, int count) {
        if (count > 0) {
            drops.add(new ItemStack(material, count));
        }
    }

    // ---------------------------------------------------------------- метка

    private String tag(Entity entity) {
        return entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private void mark(Entity entity, String value) {
        entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
    }
}
