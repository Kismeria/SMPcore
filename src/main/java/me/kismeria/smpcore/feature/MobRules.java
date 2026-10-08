package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.projectiles.ProjectileSource;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Грифинг мобов без геймрула mobGriefing (он ломает жителей: не собирают урожай, не подбирают хлеб,
 * не размножаются). Режем только разрушения от враждебных мобов. Плюс крипер-конфетти.
 */
public final class MobRules implements Listener {

    private static final Color[] PARTY = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA,
            Color.BLUE, Color.FUCHSIA, Color.PURPLE, Color.WHITE};

    private final SmpCore plugin;

    public MobRules(SmpCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Если раньше грифинг выключали геймрулом — переносим в конфиг и возвращаем геймрул,
     * чтобы жители снова работали.
     */
    public void enable() {
        boolean ruleOff = Boolean.FALSE.equals(plugin.mainWorld().getGameRuleValue(GameRules.MOB_GRIEFING));
        if (ruleOff) {
            if (!plugin.getConfig().isSet("world.mob-griefing")) {
                plugin.set("world.mob-griefing", false);
            }
            for (World world : Bukkit.getWorlds()) {
                world.setGameRule(GameRules.MOB_GRIEFING, true);
            }
            plugin.getLogger().info("Геймрул mobGriefing включён обратно — грифинг режется плагином (world.mob-griefing).");
        }
    }

    private boolean griefingOff() {
        return !plugin.flag("world.mob-griefing");
    }

    private static boolean hostile(Entity entity) {
        return entity instanceof Creeper || entity instanceof Wither || entity instanceof WitherSkull
                || entity instanceof EnderDragon || entity instanceof Enderman || entity instanceof Zombie
                || entity instanceof Silverfish || entity instanceof Ravager
                || entity instanceof Fireball fireball && fromMob(fireball.getShooter());
    }

    /** Огненный шар гаста/ифрита — да, от игрока или раздатчика — нет. */
    private static boolean fromMob(ProjectileSource shooter) {
        return shooter instanceof Entity entity && !(entity instanceof Player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (griefingOff() && hostile(event.getEntity())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        // жители, овцы, лисы и т.п. не трогаются
        if (griefingOff() && hostile(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (griefingOff() && event.getCause() == BlockIgniteEvent.IgniteCause.FIREBALL
                && event.getIgnitingEntity() instanceof Fireball fireball && fromMob(fireball.getShooter())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- крипер-конфетти

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper)) {
            return;
        }
        double chance = plugin.getConfig().getDouble("fun.creeper-confetti-chance", 10);
        if (ThreadLocalRandom.current().nextDouble(100) >= chance) {
            return;
        }
        event.setCancelled(true);
        Location at = creeper.getLocation().add(0, 1, 0);
        creeper.remove();
        Firework firework = at.getWorld().spawn(at, Firework.class, spawned -> {
            FireworkMeta meta = spawned.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(creeper.isPowered() ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.BURST)
                    .withColor(color(), color(), color())
                    .withFade(color())
                    .flicker(true)
                    .trail(true)
                    .build());
            meta.setPower(0);
            spawned.setFireworkMeta(meta);
        });
        firework.detonate();
    }

    private static Color color() {
        return PARTY[ThreadLocalRandom.current().nextInt(PARTY.length)];
    }
}
