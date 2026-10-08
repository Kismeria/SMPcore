package me.kismeria.smpcore.feature;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import io.papermc.paper.event.block.BeaconActivatedEvent;
import me.kismeria.smpcore.SmpCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BellRingEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Звуки и анимации из Vanilla Refresh: звук крафта, частицы при новом уровне и прыжке в воду,
 * анимации наковальни, стола зачарований, маяка, колокола, кровати и якоря возрождения.
 */
public final class Ambience implements Listener {

    private final SmpCore plugin;
    private final Set<UUID> inWater = new HashSet<>();
    private final Map<UUID, Float> fall = new HashMap<>();

    public Ambience(SmpCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickWater, 2L, 2L);
    }

    // ---------------------------------------------------------------- крафт (звуки Vanilla Refresh)

    private static final Particle.Spell WHITE_SPELL = new Particle.Spell(org.bukkit.Color.WHITE, 1f);

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!plugin.flag("features.craft-sounds") || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Location table = event.getInventory().getLocation();
        Location at = table != null && table.getWorld() == player.getWorld()
                ? table.toCenterLocation().add(0, 0.55, 0) : player.getLocation().add(0, 1, 0);
        World world = at.getWorld();
        // дымок над верстаком — как в Vanilla Refresh
        world.spawnParticle(Particle.WHITE_SMOKE, at, 5, 0.15, 0, 0.15, 0.03);
        world.spawnParticle(Particle.WHITE_SMOKE, at, 1, 0, 0, 0, 0);

        Material result = event.getRecipe().getResult().getType();
        String name = result.name();
        if (name.contains("NETHERITE")) {
            sound(at, Sound.BLOCK_VAULT_PLACE, 0.7f, 0.5f);
            sound(at, Sound.BLOCK_NETHERITE_BLOCK_PLACE, 1f, 0.9f);
            sound(at, Sound.BLOCK_TRIAL_SPAWNER_STEP, 1f, 1f);
            sound(at, Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 2f);
            sound(at, Sound.BLOCK_VAULT_CLOSE_SHUTTER, 0.7f, 1.2f);
            world.spawnParticle(Particle.SMALL_FLAME, at, 6, 0.15, 0.05, 0.15, 0.01);
        } else if (result == Material.MACE) {
            sound(at, Sound.BLOCK_VAULT_PLACE, 1f, 0.7f);
            sound(at, Sound.BLOCK_HEAVY_CORE_PLACE, 0.8f, 1f);
        } else if (result == Material.BEACON) {
            sound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.54f);
            sound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.5f, 1.4f);
            world.spawnParticle(Particle.END_ROD, at, 12, 0.1, 0.1, 0.1, 0.08);
        } else if (result == Material.CONDUIT) {
            sound(at, Sound.BLOCK_CONDUIT_ACTIVATE, 1f, 2f);
        } else if (result == Material.ENCHANTING_TABLE) {
            sound(at, Sound.ENTITY_VILLAGER_WORK_LIBRARIAN, 1f, 1.2f);
            sound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.4f);
            world.spawnParticle(Particle.ENCHANT, at, 40, 0.3, 0.3, 0.3, 1);
        } else if (result == Material.ANVIL) {
            sound(at, Sound.BLOCK_VAULT_PLACE, 1f, 1.2f);
            sound(at, Sound.BLOCK_ANVIL_USE, 0.4f, 2f);
            sound(at, Sound.BLOCK_TRIAL_SPAWNER_STEP, 1f, 1f);
        } else if (result == Material.ENDER_EYE) {
            sound(at, Sound.ENTITY_ENDER_EYE_DEATH, 0.8f, 1.8f);
            world.spawnParticle(Particle.PORTAL, at, 20, 0.2, 0.2, 0.2, 0.5);
        } else if (name.startsWith("DIAMOND_")) {
            sound(at, Sound.BLOCK_AMETHYST_BLOCK_STEP, 0.8f, 2f);
            sound(at, Sound.BLOCK_TRIAL_SPAWNER_STEP, 1f, 1.5f);
            world.spawnParticle(Particle.WAX_OFF, at, 6, 0.2, 0.1, 0.2, 0.5);
        } else if (name.startsWith("IRON_") || name.startsWith("CHAINMAIL_")) {
            sound(at, Sound.BLOCK_TRIAL_SPAWNER_STEP, 1f, 1f);
            sound(at, Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 2f);
        } else if (name.startsWith("COPPER_")) {
            sound(at, Sound.BLOCK_COPPER_BULB_STEP, 1f, 0.8f);
            sound(at, Sound.ITEM_ARMOR_EQUIP_COPPER, 0.7f, 2f);
        } else if (result == Material.CRAFTING_TABLE) {
            sound(at, Sound.BLOCK_VAULT_PLACE, 0.3f, 1.5f);
            sound(at, Sound.BLOCK_STONE_PLACE, 1f, 0.6f);
            sound(at, Sound.BLOCK_TRIAL_SPAWNER_STEP, 1f, 1f);
        } else if (name.endsWith("_DOOR") || name.endsWith("CHEST") || name.equals("BARREL") || name.endsWith("_TRAPDOOR")) {
            sound(at, Sound.BLOCK_CHERRY_WOOD_DOOR_CLOSE, 0.5f, 0.8f);
            sound(at, Sound.BLOCK_BAMBOO_BREAK, 1f, 0.6f);
        } else {
            sound(at, Sound.BLOCK_BAMBOO_WOOD_PLACE, 0.8f, 1.4f);
            sound(at, Sound.BLOCK_WOOD_HIT, 0.6f, 1.2f);
        }
    }

    private static void sound(Location at, Sound sound, float volume, float pitch) {
        // тише, чем в датапаке: крафтят часто
        at.getWorld().playSound(at, sound, SoundCategory.BLOCKS, volume * 0.7f, pitch);
    }

    // ---------------------------------------------------------------- игрок

    /** Каждые 5 уровней — сфера света вокруг игрока (как в Vanilla Refresh), каждые 10 — ещё и искры тотема. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevel(PlayerLevelChangeEvent event) {
        int level = event.getNewLevel();
        if (!plugin.flag("features.player-animations") || level <= event.getOldLevel() || level % 5 != 0) {
            return;
        }
        Player player = event.getPlayer();
        Location center = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();
        for (int i = 0; i < 72; i++) {
            double yaw = Math.toRadians(i * 5.0);
            double pitch = Math.toRadians(i * 20.0);
            Location point = center.clone().add(-Math.sin(yaw) * Math.cos(pitch) * 1.5, -Math.sin(pitch) * 1.5, Math.cos(yaw) * Math.cos(pitch) * 1.5);
            world.spawnParticle(Particle.EFFECT, point, 1, 0, 0, 0, 0.01, WHITE_SPELL);
        }
        world.spawnParticle(Particle.TRIAL_SPAWNER_DETECTION_OMINOUS, center.clone().add(0, -0.5, 0), 40, 0.7, 0.6, 0.7, 0.02);
        if (level % 10 == 0) {
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 30, 0.4, 0.6, 0.4, 0.3);
            player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
        }
    }

    /** Брызги, когда падаешь в воду с высоты. */
    private void tickWater() {
        if (!plugin.flag("features.player-animations")) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean water = player.isInWater();
            boolean was = water ? !inWater.add(id) : inWater.remove(id);
            Float before = fall.put(id, player.getFallDistance());
            if (water && !was && before != null && before > 2.5f) {
                Location at = player.getLocation();
                double power = Math.min(3, before / 4);
                player.getWorld().spawnParticle(Particle.SPLASH, at.clone().add(0, 0.6, 0), (int) (40 * power), 0.6, 0.2, 0.6, 0.4);
                player.getWorld().spawnParticle(Particle.BUBBLE_POP, at.clone().add(0, 0.3, 0), (int) (15 * power), 0.5, 0.1, 0.5, 0.05);
                player.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 0.8, 0), 6, 0.4, 0.1, 0.4, 0.02);
            }
        }
    }

    // ---------------------------------------------------------------- блоки

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory anvil) || event.getRawSlot() != 2
                || !plugin.flag("features.block-animations")) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        Location location = anvil.getLocation();
        if (result == null || result.getType().isAir() || location == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Location at = location.toCenterLocation().add(0, 0.6, 0);
            at.getWorld().spawnParticle(Particle.LAVA, at, 4, 0.2, 0.05, 0.2, 0);
            at.getWorld().spawnParticle(Particle.CRIT, at, 20, 0.3, 0.1, 0.3, 0.3);
            at.getWorld().spawnParticle(Particle.SMOKE, at, 6, 0.2, 0.1, 0.2, 0.02);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!plugin.flag("features.block-animations")) {
            return;
        }
        Location at = event.getEnchantBlock().getLocation().toCenterLocation().add(0, 0.8, 0);
        at.getWorld().spawnParticle(Particle.ENCHANT, at, 150, 0.6, 0.6, 0.6, 1.5);
        at.getWorld().spawnParticle(Particle.END_ROD, at, 12, 0.3, 0.3, 0.3, 0.05);
        at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.4f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBeacon(BeaconActivatedEvent event) {
        if (!plugin.flag("features.block-animations")) {
            return;
        }
        Location base = event.getBlock().getLocation().toCenterLocation();
        for (int y = 0; y < 24; y++) {
            base.getWorld().spawnParticle(Particle.END_ROD, base.clone().add(0, 0.6 + y * 0.5, 0), 2, 0.1, 0.1, 0.1, 0.01);
        }
        base.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, base.clone().add(0, 1, 0), 40, 0.5, 0.3, 0.5, 0.3);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBell(BellRingEvent event) {
        if (!plugin.flag("features.block-animations")) {
            return;
        }
        Location at = event.getBlock().getLocation().toCenterLocation();
        for (int i = 0; i < 32; i++) {
            double angle = i * Math.PI / 16;
            at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(Math.cos(angle) * 1.4, 0, Math.sin(angle) * 1.4), 1, 0, 0, 0, 0);
        }
        at.getWorld().spawnParticle(Particle.NOTE, at.clone().add(0, 1, 0), 3, 0.4, 0.2, 0.4, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnSet(PlayerSetSpawnEvent event) {
        if (!plugin.flag("features.block-animations") || event.getLocation() == null) {
            return;
        }
        Location at = event.getLocation().toCenterLocation();
        switch (event.getCause()) {
            case RESPAWN_ANCHOR -> {
                at.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0, 0.8, 0), 60, 0.4, 0.4, 0.4, 0.1);
                at.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 0.8, 0), 15, 0.3, 0.3, 0.3, 0.02);
            }
            case BED -> at.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, 0.8, 0), 4, 0.4, 0.2, 0.4, 0);
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inWater.remove(event.getPlayer().getUniqueId());
        fall.remove(event.getPlayer().getUniqueId());
    }
}
