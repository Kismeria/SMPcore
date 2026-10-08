package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Statistic;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import me.kismeria.smpcore.gui.menu.TreeChopMenu;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Рубка деревьев целиком (порт и переработка treechop.sk). Срубил бревно топором — падает всё дерево:
 * брёвна и его листва валятся в сторону от игрока (дисплеи), на земле рассыпаются в дроп.
 * Дерево — только настоящее: рядом должна быть природная (не поставленная) листва, так что
 * постройки из брёвен не сносит. Чужую листву соседних деревьев не трогает. Каждое бревно —
 * отдельное событие ломания (CoreProtect и защиты видят всё). Прочность топора с учётом
 * «Прочности», автопосадка саженцев, личный выключатель /treechop.
 */
public final class TreeChop implements TabExecutor, Listener {

    private enum Mode { FALL, SEQUENTIAL, INSTANT }

    private final SmpCore plugin;
    /** Пока плагин сам вызывает BlockBreakEvent для брёвен — свой обработчик молчит. */
    private boolean firing;
    private final Set<UUID> busy = new HashSet<>();

    public TreeChop(SmpCore plugin) {
        this.plugin = plugin;
    }

    private String opt(String key, String def) {
        String value = plugin.getConfig().getString("treechop." + key, def);
        return value == null ? def : value;
    }

    private int num(String key, int def) {
        return plugin.getConfig().getInt("treechop." + key, def);
    }

    // ---------------------------------------------------------------- команда

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length == 4 && args[0].equals("debug")) {
                debug(sender, Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                return true;
            }
            plugin.lang().send(sender, "invsee.only-players");
            return true;
        }
        new TreeChopMenu(plugin, player).open();
        return true;
    }

    /** Отладка из консоли: срубить дерево в основном мире без игрока и рассказать, что нашлось. */
    private void debug(CommandSender sender, int x, int y, int z) {
        World world = Bukkit.getWorlds().getFirst();
        Block origin = world.getBlockAt(x, y, z);
        List<Block> logs = collectLogs(origin);
        Set<Block> logSet = new HashSet<>(logs);
        logSet.add(origin);
        List<Block> foliage = collectFoliage(logSet);
        sender.sendMessage("treechop debug: origin=" + origin.getType() + " logs=" + logs.size() + " foliage=" + foliage.size());
        Location from = origin.getLocation().add(4.5, 1.6, 0.5);
        origin.setType(Material.AIR);
        fall(null, from, origin.getLocation(), logs, foliage, new ItemStack(Material.IRON_AXE), () -> sender.sendMessage("treechop debug: done"),
                drops -> sender.sendMessage("treechop debug: drops=" + drops.size()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    // ---------------------------------------------------------------- рубка

    private static boolean isLog(Material type) {
        return Tag.LOGS.isTagged(type);
    }

    /** Листва дерева: листья и «листва» незерских грибов. */
    private static boolean isFoliage(Material type) {
        return Tag.LEAVES.isTagged(type) || Tag.WART_BLOCKS.isTagged(type) || type == Material.SHROOMLIGHT;
    }

    private static boolean isAxe(ItemStack item) {
        return item != null && item.getType().name().endsWith("_AXE");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (firing || !plugin.flag("treechop.enabled")) {
            return;
        }
        Player player = event.getPlayer();
        Block origin = event.getBlock();
        ItemStack axe = player.getInventory().getItemInMainHand();
        if (!isLog(origin.getType()) || !isAxe(axe) || busy.contains(player.getUniqueId())
                || !player.hasPermission("smpcore.treechop")) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE && !plugin.flag("treechop.in-creative")) {
            return;
        }
        String activation = opt("activation", "not-sneaking").toLowerCase(Locale.ROOT);
        if (activation.equals("not-sneaking") && player.isSneaking() || activation.equals("sneaking") && !player.isSneaking()) {
            return;
        }

        List<Block> logs = collectLogs(origin);
        if (logs.isEmpty()) {
            return;
        }
        Set<Block> logSet = new HashSet<>(logs);
        logSet.add(origin);
        List<Block> foliage = collectFoliage(logSet);
        // настоящее дерево — с природной листвой. Постройки из брёвен не трогаем
        if (plugin.flag("treechop.require-leaves") && foliage.size() < num("min-leaves", 4)) {
            return;
        }

        // хватит ли прочности топора
        double multiplier = Math.max(0, plugin.getConfig().getDouble("treechop.durability-multiplier", 1.0));
        int wear = (int) Math.ceil(logs.size() * multiplier);
        if (wear > 0 && axe.getItemMeta() instanceof Damageable damageable && axe.getType().getMaxDurability() > 0
                && plugin.flag("treechop.protect-axe")) {
            int left = axe.getType().getMaxDurability() - damageable.getDamage() - 1;
            if (left < wear) {
                plugin.lang().actionBar(player, "treechop.axe-weak");
                return;
            }
        }

        // каждое бревно и лист — отдельное ломание: защиты регионов и CoreProtect
        logs = allowed(player, logs);
        boolean withLeaves = plugin.flag("treechop.break-leaves");
        foliage = withLeaves ? allowed(player, foliage) : List.of();
        if (logs.isEmpty()) {
            return;
        }

        busy.add(player.getUniqueId());
        if (wear > 0) {
            player.damageItemStack(EquipmentSlot.HAND, wear);
        }
        player.setExhaustion(player.getExhaustion() + 0.005f * logs.size());
        for (Block log : logs) {
            player.incrementStatistic(Statistic.MINE_BLOCK, log.getType());
        }

        Mode mode;
        try {
            mode = Mode.valueOf(opt("animation", "fall").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            mode = Mode.FALL;
        }
        ItemStack tool = axe.clone();
        Location base = origin.getLocation();
        List<Block> saplingSpots = saplingSpots(origin, logs);
        Material sapling = sapling(origin.getType());
        // сажаем через тик: пень ломает ваниль уже после этого обработчика
        Runnable done = () -> Bukkit.getScheduler().runTask(plugin, () -> {
            busy.remove(player.getUniqueId());
            replant(player, saplingSpots, sapling, null);
        });
        switch (mode) {
            case FALL -> fall(player, player.getEyeLocation(), base, logs, foliage, tool, () -> busy.remove(player.getUniqueId()),
                    drops -> replant(player, saplingSpots, sapling, drops));
            case SEQUENTIAL -> sequential(player, logs, foliage, tool, done);
            case INSTANT -> {
                for (Block block : logs) {
                    block.breakNaturally(tool, true, true);
                }
                for (Block block : foliage) {
                    block.breakNaturally(tool, false, true);
                }
                done.run();
            }
        }
    }

    /** Брёвна дерева обходом в ширину (соседи 3x3x3), не ниже срубленного и не дальше max-radius по горизонтали. */
    private List<Block> collectLogs(Block origin) {
        int max = num("max-logs", 150);
        int radius = num("max-radius", 10);
        List<Block> result = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        while (!queue.isEmpty() && result.size() < max) {
            Block current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block next = current.getRelative(dx, dy, dz);
                        if (seen.contains(next) || next.getY() < origin.getY()
                                || Math.abs(next.getX() - origin.getX()) > radius || Math.abs(next.getZ() - origin.getZ()) > radius) {
                            continue;
                        }
                        seen.add(next);
                        if (isLog(next.getType())) {
                            result.add(next);
                            queue.add(next);
                            if (result.size() >= max) {
                                break;
                            }
                        }
                    }
                }
            }
        }
        result.sort(Comparator.comparingInt(Block::getY));
        return result;
    }

    /**
     * Листва именно этого дерева: природная (не поставленная игроком) и наша — путь до наших брёвен
     * по листве не длиннее, чем ванильное «расстояние до ближайшего бревна». Если ближе стоит чужое
     * дерево, лист остаётся ему.
     */
    private List<Block> collectFoliage(Set<Block> logs) {
        int max = num("max-leaves", 400);
        List<Block> result = new ArrayList<>();
        Map<Block, Integer> dist = new HashMap<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        for (Block log : logs) {
            for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block next = log.getRelative(face);
                if (!dist.containsKey(next) && ours(next, 1)) {
                    dist.put(next, 1);
                    queue.add(next);
                }
            }
        }
        while (!queue.isEmpty() && result.size() < max) {
            Block current = queue.poll();
            result.add(current);
            int d = dist.get(current);
            if (d >= 6) {
                continue;
            }
            for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block next = current.getRelative(face);
                if (!dist.containsKey(next) && ours(next, d + 1)) {
                    dist.put(next, d + 1);
                    queue.add(next);
                }
            }
        }
        return result;
    }

    private static boolean ours(Block block, int distance) {
        Material type = block.getType();
        if (!isFoliage(type)) {
            return false;
        }
        if (block.getBlockData() instanceof Leaves leaves) {
            return !leaves.isPersistent() && distance <= leaves.getDistance();
        }
        // наросты и грибосвет незерских деревьев — рядом со стволом
        return distance <= 3;
    }

    /** Отдельное событие ломания для каждого блока: что запретили защиты — не трогаем. */
    private List<Block> allowed(Player player, List<Block> blocks) {
        List<Block> ok = new ArrayList<>();
        firing = true;
        try {
            for (Block block : blocks) {
                BlockBreakEvent check = new BlockBreakEvent(block, player);
                check.setDropItems(false);
                if (check.callEvent()) {
                    ok.add(block);
                }
            }
        } finally {
            firing = false;
        }
        return ok;
    }

    // ---------------------------------------------------------------- анимации

    /**
     * Дерево целиком падает от игрока. Шарнир — край пня со стороны падения (ствол не уходит в землю),
     * свет дисплеев берётся снаружи (иначе дерево чёрное — внутри пня света нет), рамка отсечения
     * на всё дерево (иначе пропадает, когда пень вне экрана). Легло — блоки сжимаются в пыль и дроп.
     */
    private void fall(Player player, Location from, Location base, List<Block> logs, List<Block> foliage, ItemStack tool, Runnable done,
                      java.util.function.Consumer<List<ItemStack>> plant) {
        World world = base.getWorld();
        Vector away = base.clone().add(0.5, 0, 0.5).toVector().subtract(from.toVector()).setY(0);
        if (away.lengthSquared() < 1e-4) {
            away = from.getDirection().setY(0);
        }
        if (away.lengthSquared() < 1e-4) {
            away = new Vector(1, 0, 0);
        }
        away.normalize();
        Vector3f axis = new Vector3f((float) away.getZ(), 0, (float) -away.getX());
        Location pivot = base.clone().add(0.5 + away.getX() * 0.5, 0, 0.5 + away.getZ() * 0.5);


        // дроп считаем сразу (с учётом топора), блоки убираем, на их месте — дисплеи
        List<ItemStack> drops = new ArrayList<>();
        List<BlockDisplay> displays = new ArrayList<>();
        List<Vector3f> offsets = new ArrayList<>();
        List<BlockData> datas = new ArrayList<>();
        // листва осыпается сразу при срубке — частицами листьев в цвет биома, со звуком
        Color tint = biomeFoliage(world, base);
        shedLeaves(world, foliage, tint);
        for (Block block : foliage) {
            drops.addAll(player != null ? block.getDrops(tool, player) : block.getDrops(tool));
            block.setType(Material.AIR, false);
        }
        for (Block block : logs) {
            drops.addAll(player != null ? block.getDrops(tool, player) : block.getDrops(tool));
            BlockData data = block.getBlockData();
            block.setType(Material.AIR, false);
            Vector3f offset = new Vector3f((float) (block.getX() - pivot.getX()), (float) (block.getY() - pivot.getY()),
                    (float) (block.getZ() - pivot.getZ()));
            // каждый блок — отдельная сущность на своём месте: отсечение (ваниль, Sodium, Entity Culling)
            // считает видимость по положению сущности, а не по точке у пня
            BlockDisplay display = world.spawn(block.getLocation(), BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.setBlock(data);
                d.setDisplayWidth(2f);
                d.setDisplayHeight(2f);
                d.setInterpolationDuration(0);
                d.setTeleportDuration(0);
            });
            displays.add(display);
            offsets.add(offset);
            datas.add(data);
        }
        world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1f, 0.7f);
        world.playSound(pivot, Sound.ENTITY_ITEM_BREAK, SoundCategory.BLOCKS, 0.5f, 0.6f);

        // дерево ложится на то, во что упрётся: землю, стену, соседний дом
        float stop = stopAngle(world, pivot, offsets, axis);
        // меньше угол — короче падение (как свободное падение: время ~ корень из угла)
        int rawDuration = Math.max(4, (int) Math.round(Math.max(6, num("fall-ticks", 24)) * Math.sqrt(stop / (Math.PI / 2))));
        int duration = rawDuration + rawDuration % 2;
        new BukkitRunnable() {
            int tick;
            float angle;

            @Override
            public void run() {
                tick += 2;
                if (tick <= duration) {
                    double t = tick / (double) duration;
                    // как под тяжестью: еле наклоняется, потом валится всё быстрее
                    angle = (float) (stop * t * t);
                    move(displays, offsets, pivot, axis, angle, 1f, 2);
                    if (tick == 2) {
                        world.playSound(pivot, Sound.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.9f, 0.5f);
                    }
                    if (tick + 2 > duration) {
                        // удар о землю
                        world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.4f, 0.5f);
                        world.playSound(pivot, Sound.ENTITY_GENERIC_SMALL_FALL, SoundCategory.BLOCKS, 1.2f, 0.5f);
                        dust(world, pivot, offsets, datas, axis, angle);
                    }
                    return;
                }
                int after = tick - duration;
                if (after == 2) {
                    // лёгкий отскок
                    move(displays, offsets, pivot, axis, angle - 0.06f, 1f, 2);
                } else if (after == 4) {
                    move(displays, offsets, pivot, axis, angle, 1f, 2);
                } else if (after == 8) {
                    // рассыпается: блоки сжимаются к своим центрам
                    move(displays, offsets, pivot, axis, angle, 0f, 6);
                } else if (after >= 14) {
                    cancel();
                    // сначала сажаем (саженец берём из дропа самого дерева), потом рассыпаем остальное
                    plant.accept(drops);
                    land(world, pivot, displays, offsets, datas, axis, angle, drops);
                    done.run();
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    /**
     * Сдвинуть дерево на угол {@code angle}: сущность блока летит туда, где окажется его угол после
     * поворота вокруг шарнира (плавно, teleportDuration), а сам блок поворачивается вокруг своего угла.
     * {@code scale} сжимает блок к его центру.
     */
    private static void move(List<BlockDisplay> displays, List<Vector3f> offsets, Location pivot, Vector3f axis,
                             float angle, float scale, int ticks) {
        Quaternionf rotation = new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, angle);
        float shrink = (1 - scale) * 0.5f;
        Vector3f translation = new Vector3f(shrink, shrink, shrink).rotate(rotation);
        Transformation transformation = new Transformation(translation, rotation, new Vector3f(scale, scale, scale), new Quaternionf());
        for (int i = 0; i < displays.size(); i++) {
            BlockDisplay display = displays.get(i);
            if (!display.isValid()) {
                continue;
            }
            Vector3f corner = new Vector3f(offsets.get(i)).rotate(rotation);
            Location to = pivot.clone().add(corner.x, corner.y, corner.z);
            to.setYaw(0);
            to.setPitch(0);
            display.setTeleportDuration(ticks);
            display.teleport(to);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(ticks);
            display.setTransformation(transformation);
        }
    }

    /** Листва срубленного дерева осыпается: падающие листочки на месте каждого листа и шелест. */
    private static void shedLeaves(World world, List<Block> foliage, Color tint) {
        if (foliage.isEmpty()) {
            return;
        }
        // на большое дерево — не больше ~200 частиц
        int perLeaf = Math.max(1, Math.min(3, 200 / foliage.size()));
        int every = Math.max(1, foliage.size() / 200);
        for (int i = 0; i < foliage.size(); i += every) {
            Block leaf = foliage.get(i);
            leafParticle(world, leaf.getLocation().add(0.5, 0.5, 0.5), leaf.getBlockData(), perLeaf, tint);
        }
        Block middle = foliage.get(foliage.size() / 2);
        Sound sound = middle.getBlockData().getSoundGroup().getBreakSound();
        world.playSound(middle.getLocation(), sound, SoundCategory.BLOCKS, 1.2f, 0.8f);
        world.playSound(foliage.getFirst().getLocation(), sound, SoundCategory.BLOCKS, 1f, 0.65f);
        world.playSound(middle.getLocation(), Sound.BLOCK_AZALEA_LEAVES_FALL, SoundCategory.BLOCKS, 1f, 0.9f);
    }

    /** Падающие листочки под цвет листвы (новые частицы листьев), у незерских наростов — крошка блока. */
    private static void leafParticle(World world, Location at, BlockData data, int count, Color tint) {
        Particle particle = switch (data.getMaterial()) {
            case CHERRY_LEAVES -> Particle.CHERRY_LEAVES;
            case PALE_OAK_LEAVES -> Particle.PALE_OAK_LEAVES;
            case RED_POPLAR_LEAVES -> Particle.RED_POPLAR_LEAVES;
            case ORANGE_POPLAR_LEAVES -> Particle.ORANGE_POPLAR_LEAVES;
            case YELLOW_POPLAR_LEAVES -> Particle.YELLOW_POPLAR_LEAVES;
            default -> Tag.LEAVES.isTagged(data.getMaterial()) ? Particle.TINTED_LEAVES : null;
        };
        if (particle == null) {
            world.spawnParticle(Particle.BLOCK, at, count * 3, 0.4, 0.4, 0.4, 0, data);
            return;
        }
        if (particle == Particle.TINTED_LEAVES) {
            world.spawnParticle(particle, at, count, 0.45, 0.45, 0.45, 0, foliageColor(data.getMaterial(), tint));
        } else {
            world.spawnParticle(particle, at, count, 0.45, 0.45, 0.45, 0);
        }
        world.spawnParticle(Particle.BLOCK, at, 2, 0.35, 0.35, 0.35, 0, data);
    }

    /** Цвет листвы как в ванилле: у ели, берёзы и мангров он свой всегда, у остальных — от биома. */
    private static Color foliageColor(Material leaves, Color biome) {
        return switch (leaves) {
            case SPRUCE_LEAVES -> Color.fromRGB(0x619961);
            case BIRCH_LEAVES -> Color.fromRGB(0x80A755);
            case MANGROVE_LEAVES -> Color.fromRGB(0x92C648);
            case AZALEA_LEAVES, FLOWERING_AZALEA_LEAVES -> Color.fromRGB(0x5C8A2F);
            default -> biome;
        };
    }

    /** Цвет листвы биома (ванильные значения; у кого цвет считается по климату — его итог). */
    private static Color biomeFoliage(World world, Location at) {
        String biome = world.getBiome(at.getBlockX(), at.getBlockY(), at.getBlockZ()).getKey().getKey();
        int rgb = switch (biome) {
            case "plains", "sunflower_plains", "stony_shore", "beach", "river", "mushroom_fields" -> 0x77AB2F;
            case "forest", "flower_forest", "dark_forest", "lush_caves", "dripstone_caves", "deep_dark" -> 0x59AE30;
            case "birch_forest", "old_growth_birch_forest" -> 0x6BA941;
            case "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga" -> 0x68A464;
            case "snowy_taiga", "snowy_plains", "ice_spikes", "frozen_river", "snowy_beach", "grove", "snowy_slopes",
                 "jagged_peaks", "frozen_peaks" -> 0x60A17B;
            case "jungle", "bamboo_jungle" -> 0x30BB0B;
            case "sparse_jungle" -> 0x3EB80F;
            case "savanna", "savanna_plateau", "windswept_savanna", "desert" -> 0xAEA42A;
            case "badlands", "eroded_badlands", "wooded_badlands" -> 0x9E814D;
            case "swamp" -> 0x6A7039;
            case "mangrove_swamp" -> 0x8DB127;
            case "meadow", "cherry_grove" -> 0x63A948;
            case "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "stony_peaks" -> 0x6DA36B;
            case "pale_garden" -> 0x878D76;
            default -> 0x59AE30;
        };
        return Color.fromRGB(rgb);
    }

    /**
     * Угол, на котором дерево упрётся во что-то твёрдое. Проверяем центры его блоков при повороте
     * шагами по 3°. Листва других деревьев, трава и цветы — не препятствие.
     */
    private static float stopAngle(World world, Location pivot, List<Vector3f> offsets, Vector3f axis) {
        float max = (float) (Math.PI / 2);
        float step = (float) Math.toRadians(3);
        for (float angle = step; angle <= max + 1e-4f; angle += step) {
            Quaternionf rotation = new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, angle);
            for (Vector3f offset : offsets) {
                Vector3f c = new Vector3f(offset).add(0.5f, 0.5f, 0.5f).rotate(rotation);
                Block block = world.getBlockAt((int) Math.floor(pivot.getX() + c.x), (int) Math.floor(pivot.getY() + c.y),
                        (int) Math.floor(pivot.getZ() + c.z));
                Material type = block.getType();
                if (type.isSolid() && !isFoliage(type) && !block.isPassable()) {
                    return Math.max(step, angle - step);
                }
            }
        }
        return max;
    }

    private static Location center(Location pivot, Vector3f offset, Vector3f axis, float angle) {
        Quaternionf rotation = new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, angle);
        Vector3f c = new Vector3f(offset).add(0.5f, 0.5f, 0.5f).rotate(rotation);
        return pivot.clone().add(c.x, c.y, c.z);
    }

    private static void dust(World world, Location pivot, List<Vector3f> offsets, List<BlockData> datas, Vector3f axis, float angle) {
        int step = Math.max(1, offsets.size() / 60);
        for (int i = 0; i < offsets.size(); i += step) {
            world.spawnParticle(Particle.BLOCK, center(pivot, offsets.get(i), axis, angle), 6, 0.35, 0.35, 0.35, 0, datas.get(i));
        }
    }

    /** Дерево рассыпалось: дисплеи убираем, дроп — по месту падения. */
    private void land(World world, Location pivot, List<BlockDisplay> displays, List<Vector3f> offsets,
                      List<BlockData> datas, Vector3f axis, float angle, List<ItemStack> drops) {
        List<Location> spots = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int step = Math.max(1, displays.size() / 60);
        for (int i = 0; i < displays.size(); i++) {
            Location spot = center(pivot, offsets.get(i), axis, angle);
            spots.add(spot);
            if (i % step == 0) {
                world.spawnParticle(Particle.BLOCK, spot, 4, 0.25, 0.25, 0.25, 0, datas.get(i));
            }
            displays.get(i).remove();
        }
        if (spots.isEmpty()) {
            spots.add(pivot);
        }
        // дроп одинаковыми пачками, чтобы не было сотни предметов
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack drop : drops) {
            boolean added = false;
            for (ItemStack stack : merged) {
                if (stack.isSimilar(drop) && stack.getAmount() + drop.getAmount() <= stack.getMaxStackSize()) {
                    stack.setAmount(stack.getAmount() + drop.getAmount());
                    added = true;
                    break;
                }
            }
            if (!added) {
                merged.add(drop.clone());
            }
        }
        boolean atBase = plugin.flag("treechop.drops-at-stump");
        for (ItemStack stack : merged) {
            Location at = atBase ? pivot.clone().add(0, 0.5, 0) : spots.get(random.nextInt(spots.size()));
            // не в стене: поднимаем до воздуха
            for (int i = 0; i < 4 && !at.getBlock().isPassable(); i++) {
                at.add(0, 1, 0);
            }
            world.dropItemNaturally(at, stack);
        }
    }

    /** Старый режим из скрипта: брёвна снизу вверх по одному, потом листва. */
    private void sequential(Player player, List<Block> logs, List<Block> foliage, ItemStack tool, Runnable done) {
        int delay = Math.max(1, num("sequential-delay-ticks", 2));
        List<Block> queue = new ArrayList<>(logs);
        queue.addAll(foliage);
        new BukkitRunnable() {
            int index;

            @Override
            public void run() {
                if (index >= queue.size() || !player.isOnline()) {
                    cancel();
                    done.run();
                    return;
                }
                Block block = queue.get(index++);
                if (isLog(block.getType())) {
                    player.swingMainHand();
                    block.breakNaturally(tool, true, true);
                } else if (isFoliage(block.getType())) {
                    block.breakNaturally(tool, index % 3 == 0, true);
                }
            }
        }.runTaskTimer(plugin, delay, delay);
    }

    // ---------------------------------------------------------------- саженцы

    /** Где рос ствол: брёвна на уровне пня, под которыми земля (у толстых деревьев их 4). */
    private static List<Block> saplingSpots(Block origin, List<Block> logs) {
        List<Block> spots = new ArrayList<>();
        spots.add(origin);
        for (Block log : logs) {
            if (log.getY() == origin.getY() && spots.size() < 4) {
                spots.add(log);
            }
        }
        return spots;
    }

    private static Material sapling(Material log) {
        String wood = log.name().replace("STRIPPED_", "").replace("_LOG", "").replace("_WOOD", "")
                .replace("_STEM", "").replace("_HYPHAE", "");
        return switch (wood) {
            case "MANGROVE" -> Material.MANGROVE_PROPAGULE;
            case "CRIMSON" -> Material.CRIMSON_FUNGUS;
            case "WARPED" -> Material.WARPED_FUNGUS;
            default -> Material.matchMaterial(wood + "_SAPLING");
        };
    }

    /** @param drops дроп упавшего дерева: саженцы берём сначала оттуда, потом из инвентаря */
    private void replant(Player player, List<Block> spots, Material sapling, List<ItemStack> drops) {
        if (!plugin.flag("treechop.replant") || sapling == null) {
            return;
        }
        boolean needSapling = plugin.flag("treechop.replant-needs-sapling");
        for (Block spot : spots) {
            Block ground = spot.getRelative(BlockFace.DOWN);
            boolean soil = sapling == Material.CRIMSON_FUNGUS || sapling == Material.WARPED_FUNGUS
                    ? Tag.NYLIUM.isTagged(ground.getType())
                    : Tag.DIRT.isTagged(ground.getType()) || sapling == Material.MANGROVE_PROPAGULE && ground.getType() == Material.MUD;
            if (!spot.getType().isAir() || !soil) {
                continue;
            }
            if (needSapling && !takeSapling(player, sapling, drops)) {
                continue;
            }
            spot.setType(sapling);
            spot.getWorld().playSound(spot.getLocation(), Sound.BLOCK_GRASS_PLACE, SoundCategory.BLOCKS, 0.8f, 1f);
            spot.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, spot.getLocation().add(0.5, 0.4, 0.5), 6, 0.3, 0.2, 0.3, 0);
        }
    }

    private static boolean takeSapling(Player player, Material sapling, List<ItemStack> drops) {
        if (drops != null) {
            for (ItemStack drop : drops) {
                if (drop.getType() == sapling && drop.getAmount() > 0) {
                    drop.setAmount(drop.getAmount() - 1);
                    if (drop.getAmount() == 0) {
                        drops.remove(drop);
                    }
                    return true;
                }
            }
        }
        ItemStack cost = new ItemStack(sapling);
        if (player.getInventory().containsAtLeast(cost, 1)) {
            player.getInventory().removeItem(cost);
            return true;
        }
        return false;
    }
}
