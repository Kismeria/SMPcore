package me.kismeria.smpcore.feature;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import io.papermc.paper.event.entity.EntityLungeEvent;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Настраиваемые кулдауны PvP-предметов. */
public final class CooldownManager implements Listener {

    private final SmpCore plugin;
    private final Map<UUID, EnumMap<CooldownType, Long>> until = new HashMap<>();

    public CooldownManager(SmpCore plugin) {
        this.plugin = plugin;
    }

    public boolean enabled(CooldownType type) {
        return plugin.getConfig().getBoolean("cooldowns." + type.key() + ".enabled", false);
    }

    public int seconds(CooldownType type) {
        return Math.max(1, plugin.getConfig().getInt("cooldowns." + type.key() + ".seconds", 5));
    }

    public void setEnabled(CooldownType type, boolean enabled) {
        plugin.set("cooldowns." + type.key() + ".enabled", enabled);
    }

    public void setSeconds(CooldownType type, int seconds) {
        plugin.set("cooldowns." + type.key() + ".seconds", seconds);
    }

    private long remainingMillis(Player player, CooldownType type) {
        EnumMap<CooldownType, Long> map = until.get(player.getUniqueId());
        if (map == null) {
            return 0;
        }
        Long end = map.get(type);
        return end == null ? 0 : Math.max(0, end - System.currentTimeMillis());
    }

    /** Если предмет на кд — пишет игроку и возвращает true. */
    private boolean blocked(Player player, CooldownType type) {
        if (!enabled(type)) {
            return false;
        }
        long left = remainingMillis(player, type);
        if (left <= 0) {
            return false;
        }
        plugin.lang().actionBar(player, "cooldown.active",
                Lang.txt("item", plugin.lang().raw(player, "cooldown.names." + type.key())),
                Lang.txt("left", String.format(Locale.ROOT, "%.1f", left / 1000.0)));
        return true;
    }

    private void begin(Player player, CooldownType type) {
        if (!enabled(type)) {
            return;
        }
        int seconds = seconds(type);
        until.computeIfAbsent(player.getUniqueId(), id -> new EnumMap<>(CooldownType.class))
                .put(type, System.currentTimeMillis() + seconds * 1000L);
        // ваниль ставит свой кд после события — перебиваем тиком позже
        if (type.visual() != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.setCooldown(type.visual(), seconds * 20);
                }
            });
        }
    }

    // ---------------------------------------------------------------- еда

    // без ignoreCancelled: клик по воздуху Bukkit присылает уже «отменённым»
    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        CooldownType type = CooldownType.byConsumable(item.getType());
        if (type == null) {
            type = CooldownType.byThrowable(item.getType());
        }
        if (type != null && blocked(event.getPlayer(), type)) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsumeCheck(PlayerItemConsumeEvent event) {
        CooldownType type = CooldownType.byConsumable(event.getItem().getType());
        if (type != null && blocked(event.getPlayer(), type)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        CooldownType type = CooldownType.byConsumable(event.getItem().getType());
        if (type != null) {
            begin(event.getPlayer(), type);
        }
    }

    // ---------------------------------------------------------------- броски

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLaunchCheck(PlayerLaunchProjectileEvent event) {
        CooldownType type = CooldownType.byThrowable(event.getItemStack().getType());
        if (type != null && blocked(event.getPlayer(), type)) {
            event.setShouldConsume(false);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(PlayerLaunchProjectileEvent event) {
        CooldownType type = CooldownType.byThrowable(event.getItemStack().getType());
        if (type != null) {
            begin(event.getPlayer(), type);
        }
    }

    // ---------------------------------------------------------------- булава

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMaceCheck(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isMaceSmash(player) && blocked(player, CooldownType.MACE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMace(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isMaceSmash(player)) {
            begin(player, CooldownType.MACE);
        }
    }

    /** Та же проверка, что у ванильной булавы: падение больше 1.5 блока и не на элитрах. */
    private static boolean isMaceSmash(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.MACE
                && player.getFallDistance() > 1.5f
                && !player.isGliding();
    }

    // ---------------------------------------------------------------- копьё, трезубец, элитры, тотем

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLunge(EntityLungeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (blocked(player, CooldownType.SPEAR_LUNGE)) {
            event.setCancelled(true);
            return;
        }
        begin(player, CooldownType.SPEAR_LUNGE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent event) {
        if (blocked(event.getPlayer(), CooldownType.TRIDENT_RIPTIDE)) {
            event.setCancelled(true);
            return;
        }
        begin(event.getPlayer(), CooldownType.TRIDENT_RIPTIDE);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        if (blocked(event.getPlayer(), CooldownType.ELYTRA_BOOST)) {
            event.setShouldConsume(false);
            event.setCancelled(true);
            return;
        }
        begin(event.getPlayer(), CooldownType.ELYTRA_BOOST);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onResurrectCheck(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player && blocked(player, CooldownType.TOTEM)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            begin(player, CooldownType.TOTEM);
        }
    }

    // ---------------------------------------------------------------- перезаход

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        EnumMap<CooldownType, Long> map = until.get(player.getUniqueId());
        if (map == null) {
            return;
        }
        long now = System.currentTimeMillis();
        map.values().removeIf(end -> end <= now);
        map.forEach((type, end) -> {
            if (type.visual() != null && enabled(type)) {
                player.setCooldown(type.visual(), (int) ((end - now) / 50));
            }
        });
    }
}
