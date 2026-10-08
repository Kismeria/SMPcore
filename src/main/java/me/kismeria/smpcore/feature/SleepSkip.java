package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Campfire;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ClockTimeSkipEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Плавный сон: когда спит достаточно игроков, ночь не обрывается, а за несколько секунд
 * проматывается — солнце и луна плывут по небу. Пропущенное время не пропадает:
 * печи, коптильни, доменки, костры и варочные стойки доделывают своё, урожай и саженцы
 * растут (ускоренные случайные тики), детёныши взрослеют.
 */
public final class SleepSkip implements Listener {

    private final SmpCore plugin;
    private final Set<UUID> animating = new HashSet<>();
    /** После проматывания ещё немного глушим повторный пропуск: игроки просыпаются не в тот же тик. */
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public SleepSkip(SmpCore plugin) {
        this.plugin = plugin;
        // сервер упал посреди проматывания — случайные тики остались ускоренными, возвращаем
        int saved = plugin.getConfig().getInt("sleep.saved-random-tick-speed", -1);
        if (saved >= 0) {
            for (World world : Bukkit.getWorlds()) {
                world.setGameRule(GameRules.RANDOM_TICK_SPEED, saved);
            }
            plugin.set("sleep.saved-random-tick-speed", null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSkip(TimeSkipEvent event) {
        if (event.getSkipReason() != ClockTimeSkipEvent.SkipReason.NIGHT_SKIP || !plugin.flag("sleep.animation")) {
            return;
        }
        World world = event.getWorld();
        UUID id = world.getUID();
        if (animating.contains(id) || System.currentTimeMillis() < cooldown.getOrDefault(id, 0L)) {
            event.setCancelled(true);
            return;
        }
        long amount = event.getSkipAmount();
        if (amount <= 0) {
            return;
        }
        event.setCancelled(true);
        animate(world, amount);
    }

    private void animate(World world, long amount) {
        UUID id = world.getUID();
        animating.add(id);
        int duration = Math.max(20, plugin.getConfig().getInt("sleep.duration-seconds", 6) * 20);
        boolean simulate = plugin.flag("sleep.simulate");
        int maxRandomTick = plugin.getConfig().getInt("sleep.max-random-tick-speed", 300);
        Integer baseRandomTick = world.getGameRuleValue(GameRules.RANDOM_TICK_SPEED);
        int base = baseRandomTick == null ? 3 : baseRandomTick;
        List<Block> machines = simulate ? machines(world) : List.of();
        if (simulate) {
            plugin.set("sleep.saved-random-tick-speed", base);
        }
        long start = world.getFullTime();

        new BukkitRunnable() {
            int tick;
            long done;

            @Override
            public void run() {
                tick++;
                // плавно: разгон и торможение (smoothstep)
                double t = Math.min(1.0, tick / (double) duration);
                long target = Math.round(amount * t * t * (3 - 2 * t));
                long delta = Math.max(0, target - done);
                done = target;
                world.setFullTime(start + done);
                if (simulate && delta > 1) {
                    world.setGameRule(GameRules.RANDOM_TICK_SPEED, (int) Math.min(maxRandomTick, base * delta));
                    for (Block block : machines) {
                        speedUp(block, delta);
                    }
                } else if (simulate) {
                    world.setGameRule(GameRules.RANDOM_TICK_SPEED, base);
                }
                if (t >= 1.0) {
                    cancel();
                    finish(world, amount, base, simulate, machines);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void finish(World world, long amount, int baseRandomTick, boolean simulate, List<Block> machines) {
        UUID id = world.getUID();
        animating.remove(id);
        cooldown.put(id, System.currentTimeMillis() + 10_000L);
        if (!simulate) {
            return;
        }
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, baseRandomTick);
        plugin.set("sleep.saved-random-tick-speed", null);
        for (Block block : machines) {
            if (block.getState(false) instanceof Furnace furnace) {
                furnace.setCookSpeedMultiplier(1.0);
            }
        }
        // детёныши взрослеют, а взрослые снова готовы размножаться — как будто ночь прошла
        int skipped = (int) Math.min(Integer.MAX_VALUE, amount);
        for (LivingEntity entity : world.getLivingEntities()) {
            if (entity instanceof Ageable ageable) {
                int age = ageable.getAge();
                if (age < 0) {
                    ageable.setAge(Math.min(0, age + skipped));
                } else if (age > 0) {
                    ageable.setAge(Math.max(0, age - skipped));
                }
            }
        }
    }

    /** Печи, костры и стойки в прогруженных чанках. */
    private static List<Block> machines(World world) {
        List<Block> list = new ArrayList<>();
        for (Chunk chunk : world.getLoadedChunks()) {
            for (BlockState state : chunk.getTileEntities(false)) {
                if (state instanceof Furnace || state instanceof BrewingStand || state instanceof Campfire) {
                    list.add(state.getBlock());
                }
            }
        }
        return list;
    }

    /** Прогнать блок-машину на {@code delta} тиков вперёд за один тик. */
    private static void speedUp(Block block, long delta) {
        BlockState state = block.getState(false);
        int extra = (int) Math.min(Integer.MAX_VALUE, delta - 1);
        switch (state) {
            case Furnace furnace -> {
                // готовка быстрее, а топливо горит с той же скоростью — сколько игровых тиков прошло, столько и сгорело
                furnace.setCookSpeedMultiplier(Math.min(200, delta));
                short burn = furnace.getBurnTime();
                if (burn > 1) {
                    furnace.setBurnTime((short) Math.max(1, burn - extra));
                }
            }
            case BrewingStand stand -> {
                int brewing = stand.getBrewingTime();
                if (brewing > 1) {
                    stand.setBrewingTime(Math.max(1, brewing - extra));
                }
            }
            case Campfire campfire -> {
                for (int slot = 0; slot < campfire.getSize(); slot++) {
                    if (campfire.getItem(slot) != null && !campfire.getItem(slot).isEmpty()) {
                        int total = campfire.getCookTimeTotal(slot);
                        campfire.setCookTime(slot, Math.min(Math.max(0, total - 1), campfire.getCookTime(slot) + extra));
                    }
                }
            }
            default -> {
            }
        }
    }

    /** Сервер выключают посреди проматывания — вернуть случайные тики. */
    public void disable() {
        int saved = plugin.getConfig().getInt("sleep.saved-random-tick-speed", -1);
        if (saved < 0) {
            return;
        }
        for (UUID id : animating) {
            World world = Bukkit.getWorld(id);
            if (world != null) {
                world.setGameRule(GameRules.RANDOM_TICK_SPEED, saved);
            }
        }
        plugin.set("sleep.saved-random-tick-speed", null);
    }
}
