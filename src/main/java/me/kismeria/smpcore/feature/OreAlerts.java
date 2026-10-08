package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Алерт админам: кто-то слишком быстро копает алмазы или древние обломки (простая замена анти-xray). */
public final class OreAlerts implements Listener {

    private enum Group {
        DIAMOND("alerts.diamond-threshold", "alerts.ore-diamond"),
        DEBRIS("alerts.debris-threshold", "alerts.ore-debris");

        final String thresholdPath;
        final String nameKey;

        Group(String thresholdPath, String nameKey) {
            this.thresholdPath = thresholdPath;
            this.nameKey = nameKey;
        }

        static Group of(Material material) {
            return switch (material) {
                case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> DIAMOND;
                case ANCIENT_DEBRIS -> DEBRIS;
                default -> null;
            };
        }
    }

    private static final long REPEAT_MS = 60_000;
    private static final int PLACED_MEMORY = 20_000;

    private final SmpCore plugin;
    private final Map<UUID, Map<Group, Deque<Long>>> mined = new HashMap<>();
    private final Map<UUID, Map<Group, Long>> lastAlert = new HashMap<>();
    /** Руды, поставленные игроками (их не считаем). */
    private final Map<String, Boolean> placed = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > PLACED_MEMORY;
        }
    };

    public OreAlerts(SmpCore plugin) {
        this.plugin = plugin;
    }

    private static String key(Block block) {
        return block.getWorld().getName() + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (Group.of(event.getBlockPlaced().getType()) != null) {
            placed.put(key(event.getBlockPlaced()), Boolean.TRUE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Group group = Group.of(event.getBlock().getType());
        Player player = event.getPlayer();
        if (group == null || !plugin.flag("alerts.enabled") || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        if (placed.remove(key(event.getBlock())) != null) {
            return;
        }
        long now = System.currentTimeMillis();
        long window = Math.max(1, plugin.getConfig().getInt("alerts.window-minutes", 10)) * 60_000L;
        Deque<Long> times = mined.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>())
                .computeIfAbsent(group, g -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > window) {
            times.removeFirst();
        }
        int threshold = Math.max(1, plugin.getConfig().getInt(group.thresholdPath, 12));
        if (times.size() < threshold) {
            return;
        }
        Map<Group, Long> last = lastAlert.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        Long previous = last.get(group);
        if (previous != null && now - previous < REPEAT_MS) {
            return;
        }
        last.put(group, now);
        alert(player, group, times.size(), window / 60_000L, event.getBlock());
    }

    private void alert(Player player, Group group, int count, long minutes, Block block) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("smpcore.alerts")) {
                send(staff, player, group, count, minutes, block);
            }
        }
        send(Bukkit.getConsoleSender(), player, group, count, minutes, block);
    }

    private void send(CommandSender to, Player player, Group group, int count, long minutes, Block block) {
        to.sendMessage(plugin.lang().get(to, "alerts.ore",
                        Lang.txt("player", player.getName()),
                        Lang.ph("count", count),
                        Lang.ph("ore", plugin.lang().raw(to, group.nameKey)),
                        Lang.ph("window", plugin.lang().duration(to, minutes * 60)),
                        Lang.ph("x", block.getX()), Lang.ph("y", block.getY()), Lang.ph("z", block.getZ()))
                .clickEvent(ClickEvent.runCommand("/tp " + player.getName())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        mined.remove(event.getPlayer().getUniqueId());
        lastAlert.remove(event.getPlayer().getUniqueId());
    }
}
