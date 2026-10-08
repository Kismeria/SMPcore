package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Боевые мелочи из Vanilla Refresh: звук смерти по причине, тотем в бездне с «режимом парения»,
 * сердцебиение на низком хп и эхо сильного удара, трезубец с верностью возвращается из бездны.
 */
public final class CombatTweaks implements Listener {

    /** Режим парения после тотема в бездне, тиков (как в Vanilla Refresh — минута). */
    private static final int FLOAT_TICKS = 60 * 20;
    private static final Particle.Spell WHITE_SPELL = new Particle.Spell(Color.WHITE, 1f);

    private final SmpCore plugin;
    private final Set<UUID> floating = new HashSet<>();
    private final Map<UUID, Long> lastHeartbeat = new HashMap<>();
    private final Set<Trident> tridents = new HashSet<>();

    public CombatTweaks(SmpCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickTridents, 2L, 2L);
    }

    // ---------------------------------------------------------------- трезубец

    private void tickTridents() {
        // трезубцы с верностью: упал ниже мира — летит обратно к владельцу
        tridents.removeIf(trident -> {
            if (!trident.isValid()) {
                return true;
            }
            if (trident.getY() < trident.getWorld().getMinHeight() && !trident.hasDealtDamage()) {
                trident.setVelocity(new Vector(0, 0.1, 0));
                trident.setHasDealtDamage(true);
                trident.getWorld().spawnParticle(Particle.END_ROD, trident.getLocation(), 10, 0.1, 0.1, 0.1, 0.05);
                trident.getWorld().playSound(trident.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 1.2f);
                return true;
            }
            return false;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (plugin.flag("features.loyal-tridents") && event.getEntity() instanceof Trident trident
                && trident.getShooter() instanceof Player && trident.getLoyaltyLevel() > 0) {
            tridents.add(trident);
        }
    }

    // ---------------------------------------------------------------- звук смерти (как в Vanilla Refresh)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        floating.remove(event.getPlayer().getUniqueId());
        if (!plugin.flag("features.death-sounds")) {
            return;
        }
        Location at = event.getPlayer().getLocation();
        if (at.getY() < at.getWorld().getMinHeight()) {
            at.setY(at.getWorld().getMinHeight());
        }
        DamageSource source = event.getDamageSource();
        DamageType type = source.getDamageType();
        Entity killer = source.getCausingEntity();
        Entity direct = source.getDirectEntity();
        Material weapon = killer instanceof Player player ? player.getInventory().getItemInMainHand().getType() : Material.AIR;

        if (type == DamageType.ARROW) {
            play(at, Sound.ENTITY_ARROW_HIT, 4f, 0.8f);
        } else if (type == DamageType.TRIDENT || direct instanceof Trident) {
            play(at, Sound.ITEM_TRIDENT_HIT_GROUND, 1f, 0.5f);
        } else if (weapon.name().endsWith("_AXE")) {
            play(at, Sound.BLOCK_NETHERRACK_BREAK, 4f, 0.5f);
            play(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.5f, 0.8f);
        } else if (weapon.name().endsWith("_SPEAR")) {
            play(at, Sound.ITEM_SPEAR_HIT, 0.4f, 1f);
            play(at, Sound.ITEM_SPEAR_ATTACK, 0.4f, 1f);
        } else if (type == DamageType.FALL || type == DamageType.STALAGMITE) {
            play(at, Sound.ENTITY_PLAYER_BIG_FALL, 1f, 1f);
            if (event.getPlayer().getFallDistance() > 20) {
                play(at, Sound.ENTITY_PLAYER_BIG_FALL, 0.5f, 0.5f);
                play(at, Sound.BLOCK_CALCITE_BREAK, 2f, 1.2f);
            }
        } else if (type == DamageType.FLY_INTO_WALL) {
            play(at, Sound.BLOCK_SOUL_SAND_BREAK, 4f, 0.5f);
            play(at, Sound.BLOCK_GRAVEL_BREAK, 4f, 0.5f);
        } else if (type == DamageType.LAVA) {
            play(at, Sound.ENTITY_GENERIC_BURN, 4f, 0.7f);
            play(at, Sound.BLOCK_LAVA_POP, 4f, 1f);
            play(at, Sound.BLOCK_LAVA_AMBIENT, 4f, 1.8f);
            play(at, Sound.ITEM_GLOW_INK_SAC_USE, 4f, 0.5f);
        } else if (type == DamageType.IN_FIRE || type == DamageType.ON_FIRE || type == DamageType.CAMPFIRE || type == DamageType.HOT_FLOOR) {
            play(at, Sound.ENTITY_GENERIC_BURN, 4f, 2f);
        } else if (type == DamageType.DROWN) {
            play(at, Sound.AMBIENT_UNDERWATER_ENTER, 1f, 0.6f);
        } else if (type == DamageType.EXPLOSION || type == DamageType.PLAYER_EXPLOSION || type == DamageType.FIREWORKS) {
            play(at, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1f);
        } else if (type == DamageType.OUT_OF_WORLD || type == DamageType.OUTSIDE_BORDER) {
            // пустота: гул долины душ (только самому, как в датапаке — но без громкости 10000)
            event.getPlayer().playSound(event.getPlayer(), Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.PLAYERS, 1f, 1.5f);
            event.getPlayer().playSound(event.getPlayer(), Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.PLAYERS, 1f, 2f);
        } else if (type == DamageType.MAGIC || type == DamageType.INDIRECT_MAGIC) {
            play(at, Sound.ENTITY_WITCH_DRINK, 4f, 1f);
        } else if (type == DamageType.WITHER) {
            play(at, Sound.ENTITY_PHANTOM_AMBIENT, 1f, 0.5f);
            play(at, Sound.ENTITY_WITHER_SKELETON_HURT, 0.5f, 0.5f);
        } else if (type == DamageType.SWEET_BERRY_BUSH) {
            play(at, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 4f, 0.8f);
        } else if (killer != null && killer.getType().name().equals("WARDEN")) {
            play(at, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 0.5f, 0.8f);
        } else if (killer != null && killer.getType().name().equals("WITHER")) {
            play(at, Sound.ENTITY_WITHER_AMBIENT, 0.5f, 1.4f);
        } else if (killer != null && killer.getType().name().equals("RAVAGER")) {
            play(at, Sound.ENTITY_RAVAGER_STUNNED, 0.5f, 1.5f);
        } else if (killer != null && killer.getType().name().equals("ENDER_DRAGON")) {
            play(at, Sound.ENTITY_ENDER_DRAGON_AMBIENT, 0.3f, 1f);
        } else if (killer instanceof Player) {
            // своё: PvP — гром трезубца, слышно рядом
            play(at, Sound.ITEM_TRIDENT_THUNDER, 0.8f, 1.2f);
        } else {
            play(at, Sound.ENTITY_PLAYER_HURT, 0.5f, 1f);
        }
    }

    private static void play(Location at, Sound sound, float volume, float pitch) {
        // 4 в датапаке слышно на ~64 блока — для сервера тише, только рядом
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, Math.min(volume, 1.5f), pitch);
    }

    // ---------------------------------------------------------------- тотем в бездне: режим парения

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVoid(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            return;
        }
        if (floating.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.flag("features.void-totem")) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        EquipmentSlot hand = inventory.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING ? EquipmentSlot.HAND
                : inventory.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING ? EquipmentSlot.OFF_HAND : null;
        if (hand == null) {
            return;
        }
        // через обычное событие воскрешения — чтобы работал кулдаун тотема
        if (!new EntityResurrectEvent(player, hand).callEvent()) {
            return;
        }
        event.setCancelled(true);
        ItemStack totem = inventory.getItem(hand);
        totem.setAmount(totem.getAmount() - 1);
        inventory.setItem(hand, totem.getAmount() > 0 ? totem : null);

        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(Math.min(max != null ? max.getValue() : 20, Math.max(1, player.getHealth())));
        player.setFallDistance(0);
        player.setVelocity(new Vector(0, 0, 0));
        player.playEffect(EntityEffect.TOTEM_RESURRECT);
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 45 * 20, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 40 * 20, 0));
        Location at = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 30, 0, 0, 0, 1.25, null, true);
        world.spawnParticle(Particle.END_ROD, at, 60, 0, 0, 0, 1.5, null, true);
        world.spawnParticle(Particle.EFFECT, at, 60, 0.3, 0.5, 0.3, 0.1, WHITE_SPELL, true);
        player.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
        plugin.lang().send(player, "totem.void");
        startFloat(player);
    }

    /**
     * Как в Vanilla Refresh: из бездны мощно вытягивает вверх, дальше минуту игрок сам парит —
     * стоишь — медленно поднимаешься, Shift — плавно спускаешься. Приземлился — режим выключается.
     */
    private void startFloat(Player player) {
        UUID id = player.getUniqueId();
        floating.add(id);
        new BukkitRunnable() {
            int t;
            int grounded;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || !floating.contains(id)) {
                    floating.remove(id);
                    cancel();
                    return;
                }
                t++;
                World world = player.getWorld();
                int min = world.getMinHeight();
                boolean deep = player.getY() < min - 2;
                if (deep) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 25, 80, true, false, false));
                } else if (player.isSneaking()) {
                    player.removePotionEffect(PotionEffectType.LEVITATION);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 25, 1, true, false, false));
                } else {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 25, 1, true, false, false));
                }
                Location at = player.getLocation().add(0, 1, 0);
                if (t <= 40) {
                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 4, 0, 0, 0, 0.8, null, true);
                    world.spawnParticle(Particle.END_ROD, at, 1, 0, 0, 0, 0.6, null, true);
                }
                if (t % 2 == 0) {
                    world.spawnParticle(Particle.EFFECT, at, 1, 0.3, 0.5, 0.3, 0.1, WHITE_SPELL, true);
                }
                if (t % 60 == 1) {
                    world.playSound(at, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.8f, 1.5f);
                }
                if (t % 10 == 0) {
                    plugin.lang().actionBar(player, t < 60 ? "totem.float-start" : "totem.float",
                            Lang.txt("seconds", (FLOAT_TICKS - t) / 20));
                }
                grounded = player.isOnGround() && !deep ? grounded + 1 : 0;
                if (t >= FLOAT_TICKS || (t > 60 && grounded > 10)) {
                    floating.remove(id);
                    player.removePotionEffect(PotionEffectType.LEVITATION);
                    if (!player.isOnGround()) {
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 30 * 20, 0));
                    }
                    player.playSound(player, Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.4f);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ---------------------------------------------------------------- сердцебиение и эхо удара

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !plugin.flag("features.low-health-sound")) {
            return;
        }
        double damage = event.getFinalDamage();
        double after = player.getHealth() + player.getAbsorptionAmount() - damage;
        // эхо сильного удара (≥ 4 сердец): затухающие повторы звука боли — только самому
        if (damage >= 8 && after > 0) {
            float[] volumes = {0.8f, 0.4f, 0.2f, 0.1f, 0.05f};
            for (int i = 0; i < volumes.length; i++) {
                float volume = volumes[i];
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.playSound(player, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, volume, 1f);
                    }
                }, 2L + i * 2L);
            }
        }
        double threshold = plugin.getConfig().getDouble("features.low-health-threshold", 6);
        if (after <= 0 || after > threshold) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastHeartbeat.get(player.getUniqueId());
        if (last != null && now - last < 2500) {
            return;
        }
        lastHeartbeat.put(player.getUniqueId(), now);
        // как в Vanilla Refresh: двойной удар сердца и третий выше — но слышит только сам игрок
        player.playSound(player, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1f, 1.5f);
        player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.5f, 1f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !player.isDead()) {
                player.playSound(player, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 0.9f, 1.8f);
            }
        }, 6L);
        player.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, player.getLocation().add(0, 1, 0), 3, 0.2, 0.2, 0.2, 0.4);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        floating.remove(event.getPlayer().getUniqueId());
        lastHeartbeat.remove(event.getPlayer().getUniqueId());
    }
}
