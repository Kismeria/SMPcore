package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Bedrock;
import me.kismeria.smpcore.util.Icon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import me.kismeria.smpcore.util.Durations;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Души (Soul Links из Vanilla Refresh): после смерти вещи и 70% опыта остаются в душе на месте смерти.
 * Забрать может только владелец — подойти вплотную. Через 15 минут душа лопается: вещи и опыт выпадают на землю.
 * Чтобы ванилла ничего не роняла, сервер держит геймрул keepInventory включённым.
 */
public final class Souls implements Listener, TabExecutor {

    private static final long LIFETIME_MS = 15 * 60_000L;
    private static final double XP_KEPT = 0.7;
    private static final double PICKUP = 1.6;
    /** Длина анимации появления, тиков. */
    private static final int INTRO = 55;
    /** Кадр анимации — раз в столько тиков. */
    private static final int PERIOD = 1;
    /** Последняя минута — душа «умирает»: краснеет и пульсирует. */
    private static final long DYING_MS = 60_000L;
    private static final Color GLOW = Color.fromRGB(0x5FE1FF);
    private static final Color DYING_GLOW = Color.fromRGB(0x8B0000);
    private static final Particle.DustOptions RUNE = new Particle.DustOptions(Color.fromRGB(0x5FE1FF), 0.7f);
    private static final Particle.DustOptions RUNE_DYING = new Particle.DustOptions(Color.fromRGB(0xFF3030), 0.7f);
    private static final int SHOWCASE = 4;

    private static final class Soul {
        UUID id;
        UUID owner;
        String ownerName;
        Location at;
        Map<Integer, ItemStack> items = new HashMap<>();
        int xp;
        long expires;
        ItemDisplay head;
        TextDisplay label;
        int frame;
        long born;
        /** Самые ценные вещи души — кружат вокруг головы. */
        final List<ItemDisplay> orbit = new ArrayList<>();
    }

    private final SmpCore plugin;
    private final File file;
    private final List<Soul> souls = new ArrayList<>();
    /** Души в анимации распада: вещи ещё не выпали. При выключении сервера выбрасываем сразу. */
    private final List<Soul> bursting = new ArrayList<>();
    private int ticks;

    public Souls(SmpCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "souls.yml");
    }

    public void enable() {
        for (World world : Bukkit.getWorlds()) {
            if (!Boolean.TRUE.equals(world.getGameRuleValue(GameRules.KEEP_INVENTORY))) {
                world.setGameRule(GameRules.KEEP_INVENTORY, true);
                plugin.getLogger().info("keepInventory включён в " + world.getName() + " — вещи после смерти уходят в души.");
            }
        }
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD, PERIOD);
    }

    public void disable() {
        for (Soul soul : souls) {
            despawn(soul);
        }
        for (Soul soul : bursting) {
            spill(soul);
        }
        bursting.clear();
        save();
    }

    // ---------------------------------------------------------------- смерть

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        Soul soul = new Soul();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && !item.getType().isAir() && !item.containsEnchantment(Enchantment.VANISHING_CURSE)) {
                soul.items.put(slot, item.clone());
            }
        }
        soul.xp = (int) Math.floor(player.calculateTotalExperiencePoints() * XP_KEPT);

        // ванилла ничего не роняет и не сохраняет — всё уходит в душу
        event.setKeepInventory(false);
        event.getDrops().clear();
        event.getItemsToKeep().clear();
        event.setKeepLevel(false);
        event.setShouldDropExperience(false);
        event.setDroppedExp(0);
        event.setNewExp(0);
        event.setNewLevel(0);
        event.setNewTotalExp(0);
        inventory.clear();

        if (soul.items.isEmpty() && soul.xp <= 0) {
            return;
        }
        Location at = safeSpot(player.getLocation());
        World world = at.getWorld();
        long now = System.currentTimeMillis();
        soul.id = UUID.randomUUID();
        soul.owner = player.getUniqueId();
        soul.ownerName = player.getName();
        soul.at = at;
        soul.born = now;
        soul.expires = now + LIFETIME_MS;
        souls.add(soul);
        save();
        birth(at, player.getLocation());
    }

    /**
     * Как в Vanilla Refresh: душа не тонет в лаве (всплывает на поверхность) и не теряется в бездне —
     * поднимается на ближайшую сушу над местом смерти, а если суши нет — на высоту 96.
     */
    private static Location safeSpot(Location death) {
        Location at = death.clone();
        World world = at.getWorld();
        int min = world.getMinHeight();
        int max = world.getMaxHeight();
        if (at.getY() >= min) {
            while (at.getBlock().isLiquid() && at.getY() < max - 2) {
                at.add(0, 1, 0);
            }
            at.setY(Math.min(at.getY(), max - 2));
            return at;
        }
        int x = at.getBlockX();
        int z = at.getBlockZ();
        Location land = landAt(world, x, z);
        for (int radius = 4; radius <= 48 && land == null; radius += 4) {
            for (int step = 0; step < 16 && land == null; step++) {
                double angle = step * Math.PI / 8;
                land = landAt(world, x + (int) Math.round(Math.cos(angle) * radius), z + (int) Math.round(Math.sin(angle) * radius));
            }
        }
        if (land != null) {
            return land;
        }
        at.setY(Math.max(min + 1, Math.min(96, max - 2)));
        return at;
    }

    private static Location landAt(World world, int x, int z) {
        boolean nether = world.getEnvironment() == World.Environment.NETHER;
        // в аду сверху бедроковая крыша — ищем пол под ней
        int top = nether ? Math.min(world.getMaxHeight() - 1, 120) : world.getHighestBlockYAt(x, z);
        for (int y = top; y > world.getMinHeight(); y--) {
            Block ground = world.getBlockAt(x, y, z);
            if (ground.getType().isSolid() && world.getBlockAt(x, y + 1, z).isPassable() && world.getBlockAt(x, y + 2, z).isPassable()) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
            if (!nether) {
                break;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- эффекты (как в Vanilla Refresh + своё)

    private static final Particle.Spell WHITE_SPELL = new Particle.Spell(Color.WHITE, 1f);
    private static final Particle.DustOptions WHITE_DUST = new Particle.DustOptions(Color.fromRGB(249, 255, 255), 0.8f);

    /** Звуки появления души — как в Vanilla Refresh, плюс столб душ из тела. */
    private void birth(Location at, Location body) {
        World world = at.getWorld();
        world.playSound(at, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 0.3f, 1.95f);
        world.playSound(at, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.5f, 0.5f);
        world.playSound(at, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.5f, 0.6f);
        world.playSound(at, Sound.PARTICLE_SOUL_ESCAPE, 1.2f, 0.7f);
        fx(world, Particle.SCULK_SOUL, body.clone().add(0, 0.8, 0), 20, 0.25, 0.6, 0.25, 0.03, null);
    }

    /** Кольцо частиц, разлетающихся от центра (wave из Vanilla Refresh). */
    private static void ring(World world, Location center, Particle particle, int count, double speed) {
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            // count = 0: смещение работает как направление полёта
            fx(world, particle, center, 0, Math.cos(angle), 0, Math.sin(angle), speed, null);
        }
    }

    /** Сфера частиц во все стороны (wave2 из Vanilla Refresh). */
    private static void sphere(World world, Location center, Particle particle, int count, double speed) {
        for (int i = 0; i < count; i++) {
            double yaw = Math.toRadians(i * 5.0);
            double pitch = Math.toRadians(i * 20.0);
            double x = -Math.sin(yaw) * Math.cos(pitch);
            double y = -Math.sin(pitch);
            double z = Math.cos(yaw) * Math.cos(pitch);
            fx(world, particle, center, 0, x, y, z, speed, null);
        }
    }

    private static void force(World world, Particle particle, Location at, int count, double spread, double speed) {
        fx(world, particle, at, count, spread, spread, spread, speed, null);
    }

    /**
     * Geyser draws several Java particles on Bedrock much bigger and longer-lived (spell swirls,
     * dust, glow, ominous sparks), so a soul turned into a white cloud there. Java players get every
     * particle; Bedrock players get this share of each kind (0 = none).
     */
    private static double bedrockShare(Particle particle, Object data) {
        if (particle == Particle.EFFECT || particle == Particle.ENTITY_EFFECT || particle == Particle.GLOW
                || particle == Particle.TRIAL_SPAWNER_DETECTION_OMINOUS || particle == Particle.ASH
                || particle == Particle.ELECTRIC_SPARK || particle == Particle.FLASH) {
            return 0;
        }
        if (particle == Particle.DUST) {
            return data == WHITE_DUST ? 0 : 0.5;
        }
        if (particle == Particle.SCULK_SOUL || particle == Particle.END_ROD) {
            return 0.25;
        }
        if (particle == Particle.SMOKE || particle == Particle.TOTEM_OF_UNDYING) {
            return 0.4;
        }
        return 1;
    }

    /** Particle for everyone in range: full set for Java, thinned per {@link #bedrockShare} for Bedrock. */
    private static void fx(World world, Particle particle, Location at, int count,
                           double dx, double dy, double dz, double speed, Object data) {
        List<Player> java = new ArrayList<>();
        List<Player> bedrock = new ArrayList<>();
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(at) > 256 * 256) {
                continue;
            }
            (Bedrock.is(viewer.getUniqueId()) ? bedrock : java).add(viewer);
        }
        if (!java.isEmpty()) {
            particle.builder().location(at).count(count).offset(dx, dy, dz).extra(speed).data(data)
                    .force(true).receivers(java).spawn();
        }
        if (bedrock.isEmpty()) {
            return;
        }
        double share = bedrockShare(particle, data);
        if (share <= 0) {
            return;
        }
        // count 0 = one directed particle: keep it with probability `share`
        int amount = count == 0 ? (Math.random() < share ? 0 : -1) : (int) Math.floor(count * share + Math.random());
        if (amount < 0 || (count > 0 && amount == 0)) {
            return;
        }
        particle.builder().location(at).count(amount).offset(dx, dy, dz).extra(speed).data(data)
                .force(true).receivers(bedrock).spawn();
    }

    // ---------------------------------------------------------------- тик

    private void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Soul> it = souls.iterator(); it.hasNext(); ) {
            Soul soul = it.next();
            World world = soul.at.getWorld();
            boolean loaded = world != null && world.isChunkLoaded(soul.at.getBlockX() >> 4, soul.at.getBlockZ() >> 4);
            if (now >= soul.expires) {
                if (loaded) {
                    shatter(soul);
                } else {
                    // чанк не прогружен — без анимации, вещи и опыт просто ложатся на место души
                    despawn(soul);
                    world.getChunkAt(soul.at);
                    spill(soul);
                }
                it.remove();
                changed = true;
                continue;
            }
            if (!loaded) {
                despawn(soul);
                continue;
            }
            soul.frame++;
            show(soul, now);
            if (soul.frame > INTRO && collect(soul, now)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    private void show(Soul soul, long now) {
        World world = soul.at.getWorld();
        Location center = soul.at.clone().add(0, 1.1, 0);
        long left = soul.expires - now;
        boolean dying = left < DYING_MS;
        int frame = soul.frame;

        // --- появление: две искрящиеся струйки по спирали поднимаются снизу к центру
        if (frame <= INTRO) {
            double t = Math.min(1, frame / 48.0);
            for (int k = 0; k < 2; k++) {
                double angle = k * Math.PI + t * 4 * Math.PI;
                double radius = 0.8 * Math.pow(1 - t, 0.7);
                Location wisp = center.clone().add(Math.cos(angle) * radius, -1.64 * (1 - t) + Math.sin(t * Math.PI) * 0.5, Math.sin(angle) * radius);
                if (k == 0) {
                    force(world, Particle.END_ROD, wisp, 1, 0, 0);
                }
                force(world, Particle.ELECTRIC_SPARK, wisp, 1, 0, 0);
                fx(world, Particle.DUST, wisp, 1, 0, 0, 0, 0, WHITE_DUST);
            }
            if (frame >= 45) {
                force(world, Particle.TRIAL_SPAWNER_DETECTION_OMINOUS, center, 3, 0, 0.05);
                force(world, Particle.END_ROD, center, 4, 0, 0.2);
            }
            if (frame == 45) {
                world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.6f);
            }
        }

        // --- голова владельца внутри сгустка (своё): вырастает к концу появления
        if (soul.head == null || !soul.head.isValid()) {
            soul.head = world.spawn(center, ItemDisplay.class, display -> {
                display.setPersistent(false);
                display.setItemStack(Icon.of(Material.PLAYER_HEAD).head(Bukkit.getOfflinePlayer(soul.owner)).build());
                display.setBillboard(Display.Billboard.FIXED);
                display.setTransformation(transform(0, 0, 0.01f));
                display.setGlowing(true);
                display.setGlowColorOverride(GLOW);
                display.setBrightness(new Display.Brightness(15, 15));
            });
        }
        float grow = (float) Math.max(0, Math.min(1, (frame - 35) / 20.0));
        float scale = 0.42f * grow + (dying ? 0.05f * (float) Math.sin(frame * 0.45) : 0);
        if (frame % 2 == 0) {
            soul.head.setInterpolationDelay(0);
            soul.head.setInterpolationDuration(2);
            soul.head.setTransformation(transform(frame * 0.07f, (float) Math.sin(frame / 12.0) * 0.1f, scale));
            soul.head.setGlowColorOverride(dying ? (frame % 12 < 6 ? Color.RED : DYING_GLOW) : GLOW);
        }

        showOrbit(soul, center, frame, grow);

        if (frame < INTRO) {
            return;
        }

        // --- своё: круг рун на земле медленно вращается, в центр стягиваются знаки стола зачарований
        if (frame % 3 == 0) {
            Location ground = soul.at.clone().add(0, 0.08, 0);
            double spin = Math.toRadians(frame * 2.0);
            for (int i = 0; i < 18; i++) {
                double a = spin + 2 * Math.PI * i / 18;
                fx(world, Particle.DUST, ground.clone().add(Math.cos(a) * 1.15, 0, Math.sin(a) * 1.15),
                        1, 0, 0, 0, 0, dying ? RUNE_DYING : RUNE);
            }
        }
        if (frame % 20 == 0) {
            force(world, Particle.ENCHANT, center.clone().add(0, -0.3, 0), 12, 0.9, 1.0);
        }

        // --- сгусток души: белое мерцание, искры, свечение
        fx(world, Particle.EFFECT, center, 1, 0.02, 0.02, 0.02, 0, WHITE_SPELL);
        if (Math.random() < 0.5) {
            force(world, Particle.END_ROD, center, 2, 0.02, 0.005);
        }
        if (Math.random() < 0.25) {
            force(world, Particle.END_ROD, center, 1, 0.5, 0.01);
        }
        if (Math.random() < 0.5) {
            force(world, Particle.GLOW, center.clone().add(0, 0.2, 0), 1, 0.3, 0.07);
        }

        // --- два потока синего огня кружат на радиусе 1 и стекают вниз (перед распадом — обычный огонь)
        if (frame % 2 == 0) {
            double angle = Math.toRadians(frame * 6.0);
            for (int k = 0; k < 2; k++) {
                double a = angle + k * Math.PI;
                Location stream = center.clone().add(Math.cos(a), 0.5, Math.sin(a));
                fx(world, dying ? Particle.FLAME : Particle.SOUL_FIRE_FLAME, stream, 0, 0, -1, 0, 0.075, null);
            }
        }

        // --- владелец рядом: зловещие искры вокруг, как в Vanilla Refresh
        Player owner = Bukkit.getPlayer(soul.owner);
        boolean ownerHere = owner != null && owner.getWorld() == world;
        double ownerDistance = ownerHere ? owner.getLocation().distance(soul.at) : Double.MAX_VALUE;
        if (ownerDistance < 10) {
            force(world, Particle.TRIAL_SPAWNER_DETECTION_OMINOUS, center, 1, 0.8, 0);
        }

        // --- своё: перед распадом дым и сердцебиение, обычно — шёпот душ
        if (dying && frame % 3 == 0) {
            force(world, Particle.SMOKE, center, 2, 0.2, 0.01);
        }
        if (frame % (dying ? 30 : 120) == 0) {
            if (dying) {
                world.playSound(soul.at, Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1.2f);
            } else {
                world.playSound(soul.at, Sound.PARTICLE_SOUL_ESCAPE, 0.5f, 0.6f + (float) Math.random() * 0.4f);
            }
        }

        // --- своё: маяк для владельца — столб синего огня, видно издалека (только ему)
        if (ownerHere && frame % 20 == 0 && ownerDistance > 6 && ownerDistance < 256) {
            for (double y = 0; y < 30; y += 1.5) {
                Particle.SOUL_FIRE_FLAME.builder().location(soul.at.clone().add(0, 2 + y, 0))
                        .count(1).offset(0, 0, 0).extra(0).force(true).receivers(owner).spawn();
            }
        }

        // --- подпись над душой и таймер над хотбаром у тех, кто рядом
        long seconds = Math.max(0, left / 1000);
        String time = String.format("%d:%02d", seconds / 60, seconds % 60);
        if (soul.label == null || !soul.label.isValid()) {
            soul.label = world.spawn(soul.at.clone().add(0, 1.9, 0), TextDisplay.class, display -> {
                display.setPersistent(false);
                display.setBillboard(Display.Billboard.CENTER);
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setShadowed(true);
                display.setBackgroundColor(Color.fromARGB(0x40000000));
            });
        }
        if (frame % 10 == 0) {
            // translate-ключ: каждый видит подпись на своём языке
            soul.label.text(Component.translatable(Lang.CLIENT_PREFIX + "soul.label", dying ? NamedTextColor.RED : NamedTextColor.AQUA,
                    Component.text(soul.ownerName, NamedTextColor.WHITE), Component.text(time, dying ? NamedTextColor.RED : NamedTextColor.GRAY)));
            for (Player viewer : world.getPlayers()) {
                if (viewer.getLocation().distanceSquared(soul.at) < 7 * 7) {
                    boolean mine = viewer.getUniqueId().equals(soul.owner);
                    plugin.lang().actionBar(viewer, mine ? "soul.actionbar" : "soul.actionbar-other",
                            Lang.txt("player", soul.ownerName), Lang.txt("time", time));
                }
            }
        }
    }

    private static Transformation transform(float angle, float bob, float scale) {
        return new Transformation(new Vector3f(0, bob, 0), new AxisAngle4f(angle, 0, 1, 0),
                new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    /** Вещи и опыт души выпадают на её месте. */
    private void spill(Soul soul) {
        World world = soul.at.getWorld();
        if (world == null) {
            return;
        }
        Location drop = soul.at.clone().add(0, 0.6, 0);
        for (ItemStack item : soul.items.values()) {
            if (item != null && !item.getType().isAir()) {
                world.dropItemNaturally(drop, item);
            }
        }
        soul.items.clear();
        int xp = soul.xp;
        soul.xp = 0;
        // несколькими шарами, чтобы разлетелись
        int parts = Math.max(1, Math.min(8, xp / 10));
        for (int i = 0; i < parts && xp > 0; i++) {
            int amount = i == parts - 1 ? xp : xp / (parts - i);
            xp -= amount;
            world.spawn(drop, ExperienceOrb.class, orb -> orb.setExperience(amount));
        }
    }

    /** Распад по таймеру — последовательность из Vanilla Refresh (shatter): ~7 секунд. */
    private void shatter(Soul soul) {
        World world = soul.at.getWorld();
        Location center = soul.at.clone().add(0, 1.1, 0);
        if (soul.label != null) {
            soul.label.remove();
            soul.label = null;
        }
        ItemDisplay head = soul.head;
        soul.head = null;
        List<ItemDisplay> orbit = new ArrayList<>(soul.orbit);
        soul.orbit.clear();
        bursting.add(soul);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                force(world, Particle.TRIAL_SPAWNER_DETECTION_OMINOUS, center, 1, 0, 0.02);
                if (t == 10) {
                    world.playSound(center, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 4f, 0.65f);
                }
                if (t == 40) {
                    // душа лопается — вещи и опыт разлетаются, их может подобрать кто угодно
                    for (ItemDisplay item : orbit) {
                        item.remove();
                    }
                    bursting.remove(soul);
                    spill(soul);
                    world.playSound(center, Sound.ENTITY_ITEM_PICKUP, 1.2f, 0.5f);
                    force(world, Particle.ASH, soul.at.clone().add(0, 0.2, 0), 25, 0.5, 0.02);
                }
                if (head != null && head.isValid() && t % 2 == 0 && t < 40) {
                    // голову трясёт всё сильнее
                    float shake = t / 40f * 0.08f;
                    head.setInterpolationDelay(0);
                    head.setInterpolationDuration(2);
                    head.setTransformation(new Transformation(
                            new Vector3f((float) (Math.random() - 0.5) * shake * 2, 0, (float) (Math.random() - 0.5) * shake * 2),
                            new AxisAngle4f(t * 0.3f, 0, 1, 0), new Vector3f(0.42f, 0.42f, 0.42f), new AxisAngle4f()));
                }
                if (t == 39 || t == 40) {
                    fx(world, Particle.FLASH, center, 3, 0, 0, 0, 0.3, Color.WHITE);
                }
                if (t == 40) {
                    world.playSound(center, Sound.ENTITY_ENDER_EYE_DEATH, 4f, 0.7f);
                    world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 2f, 0.55f);
                    force(world, Particle.END_ROD, center, 50, 0, 0.2);
                    force(world, Particle.END_ROD, center, 50, 0, 0.4);
                    force(world, Particle.SCULK_SOUL, center, 30, 0, 0.2);
                    if (head != null && head.isValid()) {
                        head.setInterpolationDelay(0);
                        head.setInterpolationDuration(6);
                        head.setTransformation(transform(6f, 0.4f, 0f));
                        Bukkit.getScheduler().runTaskLater(plugin, head::remove, 7L);
                    }
                }
                if (t > 40 && t <= 120) {
                    force(world, Particle.SCULK_SOUL, center, 1, 0, 0.05);
                }
                if (t >= 140) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** @return true, если владелец забрал душу */
    private boolean collect(Soul soul, long now) {
        Player owner = Bukkit.getPlayer(soul.owner);
        if (owner == null || owner.getWorld() != soul.at.getWorld() || owner.isDead() || owner.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        // как в Vanilla Refresh: подойти почти вплотную
        if (owner.getLocation().distanceSquared(soul.at) > PICKUP * PICKUP) {
            return false;
        }
        give(owner, soul);
        found(soul, owner);
        return true;
    }

    /** Душа найдена — последовательность из Vanilla Refresh (soul_found) + голова влетает в игрока. */
    private void found(Soul soul, Player player) {
        World world = soul.at.getWorld();
        Location center = soul.at.clone().add(0, 1.1, 0);
        if (soul.label != null) {
            soul.label.remove();
            soul.label = null;
        }
        world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 3f, 2f);
        world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 3f, 1.75f);
        world.playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, 3f, 0.65f);
        world.playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, 3f, 0.65f);
        world.playSound(center, Sound.ENTITY_GLOW_SQUID_SQUIRT, 3f, 0.6f);
        world.playSound(center, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 3f, 1.2f);
        world.playSound(center, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 3f, 0.6f);
        for (int i = 0; i < 3; i++) {
            world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 3f, 0.5f);
        }
        ring(world, center, Particle.SCULK_SOUL, 72, 0.3);
        sphere(world, center, Particle.END_ROD, 72, 0.6);

        List<ItemDisplay> orbit = new ArrayList<>(soul.orbit);
        soul.orbit.clear();
        for (int k = 0; k < orbit.size(); k++) {
            ItemDisplay item = orbit.get(k);
            int index = k;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!item.isValid()) {
                    return;
                }
                if (!player.isOnline()) {
                    item.remove();
                    return;
                }
                item.setTeleportDuration(6);
                item.teleport(player.getLocation().add(0, 1, 0));
                item.setInterpolationDelay(0);
                item.setInterpolationDuration(6);
                item.setTransformation(transform(2f, 0, 0.05f));
                player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1f + index * 0.2f);
                Bukkit.getScheduler().runTaskLater(plugin, item::remove, 7L);
            }, 3L + k * 3L);
        }
        ItemDisplay head = soul.head;
        soul.head = null;
        if (head != null && head.isValid()) {
            head.setTeleportDuration(10);
            head.teleport(player.getEyeLocation());
            head.setInterpolationDelay(0);
            head.setInterpolationDuration(10);
            head.setTransformation(transform(5f, 0, 0.05f));
            Bukkit.getScheduler().runTaskLater(plugin, head::remove, 11L);
        }
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                if (t <= 5) {
                    fx(world, Particle.FLASH, center, 4, 0, 0, 0, 0, Color.WHITE);
                }
                if (t <= 20) {
                    force(world, Particle.SCULK_SOUL, center, 3, 0, 0.1);
                    force(world, Particle.SCULK_SOUL, center, 2, 0, 0.2);
                }
                if (t <= 50) {
                    force(world, Particle.SCULK_SOUL, center, 1, 0, 0.08);
                }
                if (t <= 40) {
                    fx(world, Particle.EFFECT, center, 1, 0, 0, 0, 0.25, WHITE_SPELL);
                }
                if (player.isOnline() && (t <= 30 || (t <= 50 && Math.random() < 0.5))) {
                    force(player.getWorld(), Particle.SCULK_SOUL, player.getLocation().add(0, 1, 0), 1, 0, 0.06);
                }
                if (t == 10 && player.isOnline()) {
                    // своё: спираль синего огня вокруг игрока и короткая защита
                    Location feet = player.getLocation();
                    for (int i = 0; i < 40; i++) {
                        double angle = i * Math.PI / 8;
                        fx(player.getWorld(), Particle.SOUL_FIRE_FLAME,
                                feet.clone().add(Math.cos(angle) * 0.8, i * 0.06, Math.sin(angle) * 0.8), 1, 0, 0, 0, 0, null);
                    }
                    fx(player.getWorld(), Particle.TOTEM_OF_UNDYING, feet.clone().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.35, null);
                    player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 0));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 200, 0));
                }
                if (t >= 65) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Вещи — по своим слотам (броня снова на теле), занятые слоты — в свободные, остаток — под ноги. */
    private static void give(Player player, Soul soul) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> rest = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> entry : soul.items.entrySet()) {
            int slot = entry.getKey();
            ItemStack current = slot < inventory.getSize() ? inventory.getItem(slot) : null;
            if (slot < inventory.getSize() && (current == null || current.getType().isAir())) {
                inventory.setItem(slot, entry.getValue());
            } else {
                rest.add(entry.getValue());
            }
        }
        for (ItemStack item : rest) {
            for (ItemStack left : inventory.addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
        if (soul.xp > 0) {
            player.giveExp(soul.xp);
        }
    }

    private static void despawn(Soul soul) {
        for (ItemDisplay item : soul.orbit) {
            item.remove();
        }
        soul.orbit.clear();
        if (soul.head != null) {
            soul.head.remove();
            soul.head = null;
        }
        if (soul.label != null) {
            soul.label.remove();
            soul.label = null;
        }
    }

    // ---------------------------------------------------------------- витрина вещей

    /** До 4 самых ценных вещей кружат вокруг головы — видно, что внутри души. */
    private void showOrbit(Soul soul, Location center, int frame, float grow) {
        World world = center.getWorld();
        if (soul.orbit.isEmpty() && frame >= INTRO - 15) {
            for (ItemStack item : showcase(soul)) {
                soul.orbit.add(world.spawn(center, ItemDisplay.class, display -> {
                    display.setPersistent(false);
                    display.setItemStack(item.asOne());
                    display.setBillboard(Display.Billboard.FIXED);
                    display.setTransformation(transform(0, 0, 0.01f));
                    display.setBrightness(new Display.Brightness(15, 15));
                }));
            }
        }
        if (frame % 2 != 0) {
            return;
        }
        int count = soul.orbit.size();
        for (int k = 0; k < count; k++) {
            ItemDisplay display = soul.orbit.get(k);
            if (!display.isValid()) {
                continue;
            }
            double angle = Math.toRadians(-frame * 3.0) + 2 * Math.PI * k / count;
            float radius = 0.85f * grow;
            float y = (float) Math.sin(frame / 10.0 + k) * 0.15f;
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(2);
            display.setTransformation(new Transformation(
                    new Vector3f((float) Math.cos(angle) * radius, y, (float) Math.sin(angle) * radius),
                    new AxisAngle4f(frame * 0.1f + k, 0, 1, 0),
                    new Vector3f(0.32f * grow, 0.32f * grow, 0.32f * grow), new AxisAngle4f()));
        }
    }

    private static List<ItemStack> showcase(Soul soul) {
        List<ItemStack> sorted = new ArrayList<>(soul.items.values());
        sorted.sort((a, b) -> Integer.compare(worth(b), worth(a)));
        List<ItemStack> picked = new ArrayList<>();
        java.util.Set<Material> seen = new java.util.HashSet<>();
        for (ItemStack item : sorted) {
            if (seen.add(item.getType())) {
                picked.add(item);
            }
            if (picked.size() == SHOWCASE) {
                break;
            }
        }
        return picked;
    }

    private static int worth(ItemStack item) {
        String name = item.getType().name();
        int score = item.getEnchantments().size() * 15;
        if (name.contains("NETHERITE")) {
            score += 100;
        } else if (name.equals("ELYTRA") || name.equals("TOTEM_OF_UNDYING") || name.equals("MACE") || name.equals("TRIDENT")
                || name.contains("SHULKER_BOX") || name.equals("NETHER_STAR") || name.equals("BEACON")) {
            score += 90;
        } else if (name.contains("DIAMOND")) {
            score += 60;
        } else if (name.contains("SWORD") || name.contains("PICKAXE") || name.contains("AXE") || name.contains("HELMET")
                || name.contains("CHESTPLATE") || name.contains("LEGGINGS") || name.contains("BOOTS") || name.contains("BOW")) {
            score += 25;
        }
        return score;
    }

    // ---------------------------------------------------------------- /shatter

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player admin)) {
            plugin.lang().send(sender, "invsee.only-players");
            return true;
        }
        Soul nearest = null;
        double best = 64 * 64;
        for (Soul soul : souls) {
            if (soul.at.getWorld() == admin.getWorld()) {
                double distance = soul.at.distanceSquared(admin.getLocation());
                if (distance < best) {
                    best = distance;
                    nearest = soul;
                }
            }
        }
        if (nearest == null) {
            plugin.lang().send(admin, "soul.admin-none");
            return true;
        }
        long now = System.currentTimeMillis();
        if (args.length == 0) {
            nearest.expires = now;
            plugin.lang().send(admin, "soul.admin-shattered", Lang.txt("player", nearest.ownerName));
        } else {
            long seconds = Durations.parseDurationSeconds(args[0]);
            if (seconds <= 0) {
                plugin.lang().send(admin, "soul.admin-usage");
                return true;
            }
            nearest.expires = now + seconds * 1000L;
            plugin.lang().send(admin, "soul.admin-time", Lang.txt("player", nearest.ownerName),
                    Lang.txt("time", plugin.lang().duration(admin, seconds)));
        }
        save();
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("30s", "1m", "5m", "15m", "1h") : List.of();
    }

    // ---------------------------------------------------------------- souls.yml

    private void load() {
        souls.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("souls");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            World world = Bukkit.getWorld(section.getString("world", ""));
            if (world == null) {
                continue;
            }
            Soul soul = new Soul();
            soul.id = UUID.fromString(key);
            soul.owner = UUID.fromString(section.getString("owner", key));
            soul.ownerName = section.getString("name", "?");
            soul.at = new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"));
            soul.xp = section.getInt("xp");
            soul.expires = section.getLong("expires");
            soul.frame = INTRO;
            ConfigurationSection items = section.getConfigurationSection("items");
            if (items != null) {
                for (String slot : items.getKeys(false)) {
                    ItemStack item = items.getItemStack(slot);
                    if (item != null) {
                        soul.items.put(Integer.parseInt(slot), item);
                    }
                }
            }
            souls.add(soul);
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Soul soul : souls) {
            String path = "souls." + soul.id;
            yaml.set(path + ".owner", soul.owner.toString());
            yaml.set(path + ".name", soul.ownerName);
            yaml.set(path + ".world", soul.at.getWorld().getName());
            yaml.set(path + ".x", soul.at.getX());
            yaml.set(path + ".y", soul.at.getY());
            yaml.set(path + ".z", soul.at.getZ());
            yaml.set(path + ".xp", soul.xp);
            yaml.set(path + ".expires", soul.expires);
            for (Map.Entry<Integer, ItemStack> entry : soul.items.entrySet()) {
                yaml.set(path + ".items." + entry.getKey(), entry.getValue());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить souls.yml: " + e.getMessage());
        }
    }
}
