package me.kismeria.smpcore.feature;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * /vanish: админа не видно в мире, табе, списке серверов, локатор-баре; нет сообщений о входе/выходе,
 * достижениях и смерти; мобы и скалк-сенсоры не реагируют. Летать, ломать, ставить, кидать и
 * подбирать можно.
 */
public final class VanishManager implements Listener, TabExecutor {

    private final SmpCore plugin;
    private final NamespacedKey waypointKey;
    private final VanishStash stash;

    public VanishManager(SmpCore plugin) {
        this.plugin = plugin;
        this.waypointKey = new NamespacedKey(plugin, "vanish_waypoint");
        this.stash = new VanishStash(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::remind, 40L, 40L);
    }

    private Set<UUID> vanished() {
        return plugin.state().vanished();
    }

    public boolean isVanished(Player player) {
        return vanished().contains(player.getUniqueId());
    }

    public int vanishedOnline() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isVanished(player)) {
                count++;
            }
        }
        return count;
    }

    /** Видит ли {@code viewer} игрока {@code target} в ванише. */
    public boolean canSee(Player viewer, Player target) {
        return viewer.equals(target) || !isVanished(target)
                || (plugin.flag("vanish.staff-see") && viewer.hasPermission("smpcore.vanish.see"));
    }

    // ---------------------------------------------------------------- переключение

    public void set(Player player, boolean vanish) {
        if (vanish == isVanished(player)) {
            return;
        }
        if (vanish) {
            vanished().add(player.getUniqueId());
        } else {
            vanished().remove(player.getUniqueId());
        }
        plugin.state().save();
        // отдельный инвентарь на время ваниша: своё уходит в хранилище, при выходе возвращается
        if (plugin.flag("vanish.separate-inventory")) {
            if (vanish) {
                stash.store(player);
            } else {
                stash.restore(player);
            }
        }
        apply(player);
        // при скрытых сообщениях о входе/выходе фейковые «зашёл»/«вышел» тоже не нужны
        if (plugin.flag("vanish.fake-messages")
                && !plugin.flag(vanish ? "chat.hide-quit-messages" : "chat.hide-join-messages")) {
            Component message = Component.translatable(vanish ? "multiplayer.player.left" : "multiplayer.player.joined",
                    NamedTextColor.YELLOW, player.displayName());
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other != player && !(plugin.flag("vanish.staff-see") && other.hasPermission("smpcore.vanish.see"))) {
                    other.sendMessage(message);
                }
            }
        }
        plugin.lang().send(player, vanish ? "vanish.on" : "vanish.off");
    }

    /** Применяет состояние ваниша: видимость и способности. */
    private void apply(Player player) {
        boolean vanish = isVanished(player);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == player) {
                continue;
            }
            if (canSee(other, player)) {
                other.showPlayer(plugin, player);
            } else {
                other.hidePlayer(plugin, player);
            }
        }
        player.setSleepingIgnored(vanish);
        player.setCollidable(!vanish);
        player.setAffectsSpawning(!vanish);
        player.setSilent(vanish);
        AttributeInstance waypoint = player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        if (waypoint != null) {
            waypoint.removeModifier(waypointKey);
            if (vanish) {
                waypoint.addTransientModifier(new AttributeModifier(waypointKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
        boolean creativeLike = player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
        if (vanish && plugin.flag("vanish.fly")) {
            player.setAllowFlight(true);
        } else if (!vanish && !creativeLike) {
            if (player.isFlying()) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 200, 0, false, false));
            }
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private void remind() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isVanished(player)) {
                plugin.lang().actionBar(player, "vanish.bar");
            }
        }
    }

    // ---------------------------------------------------------------- вход и выход

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        if (isVanished(joined)) {
            event.joinMessage(null);
            apply(joined);
            plugin.lang().send(joined, "vanish.still");
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != joined && isVanished(other) && !canSee(joined, other)) {
                joined.hidePlayer(plugin, other);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (isVanished(event.getPlayer())) {
            event.quitMessage(null);
        }
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (isVanished(event.getPlayer())) {
            Bukkit.getScheduler().runTask(plugin, () -> apply(event.getPlayer()));
        }
    }

    // ---------------------------------------------------------------- скрытые сообщения

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (isVanished(event.getPlayer())) {
            event.message(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (isVanished(event.getEntity())) {
            event.deathMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!isVanished(player) || !plugin.flag("vanish.block-chat")) {
            return;
        }
        String text = Text.plain(event.message());
        if (text.startsWith("!") && text.length() > 1) {
            event.message(Component.text(text.substring(1)));
            return;
        }
        event.setCancelled(true);
        plugin.lang().send(player, "vanish.chat-blocked");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPing(PaperServerListPingEvent event) {
        int hidden = vanishedOnline();
        if (hidden == 0) {
            return;
        }
        event.setNumPlayers(Math.max(0, event.getNumPlayers() - hidden));
        event.getListedPlayers().removeIf(info -> vanished().contains(info.id()));
    }

    // ---------------------------------------------------------------- мир не реагирует

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPhysical(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && isVanished(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVibration(BlockReceiveGameEvent event) {
        if (event.getEntity() instanceof Player player && isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isVanished(player) && plugin.flag("vanish.god")
                && event.getCause() != EntityDamageEvent.DamageCause.VOID && event.getCause() != EntityDamageEvent.DamageCause.KILL) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isVanished(player) && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- команда

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target;
        if (args.length > 0) {
            if (!sender.hasPermission("smpcore.vanish.others")) {
                plugin.lang().send(sender, "vanish.no-permission");
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                plugin.lang().send(sender, "punish.not-found", Lang.txt("player", args[0]));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            plugin.lang().send(sender, "vanish.usage");
            return true;
        }
        set(target, !isVanished(target));
        if (target != sender) {
            plugin.lang().send(sender, isVanished(target) ? "vanish.other-on" : "vanish.other-off", Lang.txt("player", target.getName()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("smpcore.vanish.others")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }
}
