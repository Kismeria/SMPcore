package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.StateStore.Phase;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ограничения добычи после старта:
 * глубинносланцевые руды закрыты N минут, а глубинный сланец, туф и т.п. в это время копаются медленнее;
 * древние обломки и улучшение до незерита закрыты отдельным таймером.
 */
public final class MiningLock implements Listener {

    private static final long NOTICE_COOLDOWN_MS = 4000;

    private final SmpCore plugin;
    private final NamespacedKey slowKey;
    private final Set<UUID> slowed = new HashSet<>();
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    public MiningLock(SmpCore plugin) {
        this.plugin = plugin;
        this.slowKey = new NamespacedKey(plugin, "slow_dig");
    }

    // ---------------------------------------------------------------- глубинные руды

    public boolean enabled() {
        return plugin.flag("mining.deepslate-lock");
    }

    public int minutes() {
        return Math.max(1, plugin.getConfig().getInt("mining.deepslate-lock-minutes", 120));
    }

    public long unlockAt() {
        return plugin.state().startedAt() + minutes() * 60_000L;
    }

    public boolean locked(long now) {
        return enabled() && plugin.state().phase() == Phase.RUNNING && now < unlockAt();
    }

    public static boolean isDeepslateOre(Material material) {
        String name = material.name();
        return name.startsWith("DEEPSLATE_") && name.endsWith("_ORE");
    }

    // ---------------------------------------------------------------- медленное копание

    public boolean slowDigEnabled() {
        return plugin.flag("mining.slow-dig");
    }

    public int slowDigFactor() {
        return Math.max(2, plugin.getConfig().getInt("mining.slow-dig-factor", 3));
    }

    private boolean slowDigActive(long now) {
        return slowDigEnabled() && locked(now);
    }

    private Set<Material> slowBlocks() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("mining.slow-dig-blocks")) {
            Material material = Material.matchMaterial(name);
            if (material != null && material.isBlock()) {
                set.add(material);
            }
        }
        return set;
    }

    // ---------------------------------------------------------------- незерит

    public boolean netheriteEnabled() {
        return plugin.flag("mining.netherite-lock");
    }

    public int netheriteMinutes() {
        return Math.max(1, plugin.getConfig().getInt("mining.netherite-lock-minutes", 1440));
    }

    public long netheriteUnlockAt() {
        return plugin.state().startedAt() + netheriteMinutes() * 60_000L;
    }

    public boolean netheriteLocked(long now) {
        return netheriteEnabled() && plugin.state().phase() == Phase.RUNNING && now < netheriteUnlockAt();
    }

    // ---------------------------------------------------------------- статус

    public String status(CommandSender viewer) {
        return status(viewer, enabled(), unlockAt());
    }

    public String netheriteStatus(CommandSender viewer) {
        return status(viewer, netheriteEnabled(), netheriteUnlockAt());
    }

    private String status(CommandSender viewer, boolean enabled, long unlockAt) {
        if (!enabled) {
            return plugin.lang().raw(viewer, "status.disabled");
        }
        return switch (plugin.state().phase()) {
            case IDLE -> plugin.lang().raw(viewer, "status.waiting");
            case LOBBY -> plugin.lang().raw(viewer, "status.with-start");
            case RUNNING -> {
                long left = (unlockAt - System.currentTimeMillis()) / 1000;
                yield left > 0
                        ? plugin.lang().raw(viewer, "status.left").replace("<left>", plugin.lang().duration(viewer, left))
                        : plugin.lang().raw(viewer, "status.open");
            }
        };
    }

    // ---------------------------------------------------------------- тик (из StartManager)

    void checkAnnounce(long now) {
        if (plugin.state().phase() != Phase.RUNNING) {
            return;
        }
        if (!slowed.isEmpty() && !slowDigActive(now)) {
            for (UUID id : Set.copyOf(slowed)) {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null) {
                    removeSlow(player);
                } else {
                    slowed.remove(id);
                }
            }
        }
        if (enabled() && !plugin.state().deepslateAnnounced() && now >= unlockAt()) {
            plugin.state().deepslateAnnounced(true);
            plugin.state().save();
            plugin.lang().broadcast("mining.deepslate-opened");
            chime(Sound.BLOCK_AMETHYST_BLOCK_CHIME);
        }
        if (netheriteEnabled() && !plugin.state().netheriteAnnounced() && now >= netheriteUnlockAt()) {
            plugin.state().netheriteAnnounced(true);
            plugin.state().save();
            plugin.lang().broadcast("mining.netherite-opened");
            chime(Sound.BLOCK_SMITHING_TABLE_USE);
        }
    }

    private void chime(Sound sound) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player, sound, 1f, 1.2f);
        }
    }

    // ---------------------------------------------------------------- ломание

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Material type = event.getBlock().getType();
        long now = System.currentTimeMillis();
        if (isDeepslateOre(type) && locked(now)) {
            event.setCancelled(true);
            plugin.lang().actionBar(player, "mining.deepslate-locked-bar",
                    Lang.ph("left", plugin.lang().duration(player, (unlockAt() - now) / 1000)));
        } else if (type == Material.ANCIENT_DEBRIS && netheriteLocked(now)) {
            event.setCancelled(true);
            plugin.lang().actionBar(player, "mining.netherite-locked-bar",
                    Lang.ph("left", plugin.lang().duration(player, (netheriteUnlockAt() - now) / 1000)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBroken(BlockBreakEvent event) {
        removeSlow(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDigStart(BlockDamageEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        boolean slow = player.getGameMode() != GameMode.CREATIVE
                && slowDigActive(now)
                && slowBlocks().contains(event.getBlock().getType());
        if (!slow) {
            removeSlow(player);
            return;
        }
        applySlow(player);
        Long last = lastNotice.get(player.getUniqueId());
        if (last == null || now - last > NOTICE_COOLDOWN_MS) {
            lastNotice.put(player.getUniqueId(), now);
            plugin.lang().actionBar(player, "mining.slow-dig-bar",
                    Lang.ph("factor", slowDigFactor()),
                    Lang.ph("left", plugin.lang().duration(player, (unlockAt() - now) / 1000)));
        }
    }

    @EventHandler
    public void onDigAbort(BlockDamageAbortEvent event) {
        removeSlow(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeSlow(event.getPlayer());
        lastNotice.remove(event.getPlayer().getUniqueId());
    }

    private void applySlow(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attribute == null || attribute.getModifier(slowKey) != null) {
            return;
        }
        // итоговая скорость × (1 + amount) = скорость / factor
        attribute.addTransientModifier(new AttributeModifier(slowKey, 1.0 / slowDigFactor() - 1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        slowed.add(player.getUniqueId());
    }

    private void removeSlow(Player player) {
        if (!slowed.remove(player.getUniqueId())) {
            return;
        }
        AttributeInstance attribute = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attribute != null) {
            attribute.removeModifier(slowKey);
        }
    }

    // ---------------------------------------------------------------- взрывы и кузня

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        strip(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        strip(event.blockList());
    }

    private void strip(List<Block> blocks) {
        long now = System.currentTimeMillis();
        boolean ores = locked(now);
        boolean debris = netheriteLocked(now);
        if (ores || debris) {
            blocks.removeIf(block -> (ores && isDeepslateOre(block.getType()))
                    || (debris && block.getType() == Material.ANCIENT_DEBRIS));
        }
    }

    @EventHandler
    public void onSmithing(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();
        long now = System.currentTimeMillis();
        if (result == null || !netheriteLocked(now) || !result.getType().name().toUpperCase(Locale.ROOT).startsWith("NETHERITE_")) {
            return;
        }
        event.setResult(null);
        HumanEntity viewer = event.getView().getPlayer();
        if (viewer instanceof Player player) {
            Long last = lastNotice.get(player.getUniqueId());
            if (last == null || now - last > NOTICE_COOLDOWN_MS) {
                lastNotice.put(player.getUniqueId(), now);
                plugin.lang().actionBar(player, "mining.netherite-smithing-bar",
                        Lang.ph("left", plugin.lang().duration(player, (netheriteUnlockAt() - now) / 1000)));
            }
        }
    }
}
