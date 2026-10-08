package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Зелье невидимости прячет ник: в табе, в сообщениях о смерти/убийстве, по желанию — в чате. */
public final class InvisibilityManager implements Listener {

    private final SmpCore plugin;
    private final Set<UUID> hidden = new HashSet<>();

    public InvisibilityManager(SmpCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, 10L);
    }

    private boolean option(String key) {
        return plugin.flag("invisibility." + key);
    }

    public boolean isHidden(Player player) {
        return hidden.contains(player.getUniqueId());
    }

    public boolean hideInChat(Player player) {
        return option("hide-in-chat") && isHidden(player);
    }

    private boolean shouldHide(Player player) {
        if (!option("enabled") || player.getGameMode() == GameMode.SPECTATOR
                || !player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            return false;
        }
        if (option("require-no-armor")) {
            for (ItemStack armor : player.getInventory().getArmorContents()) {
                if (armor != null && !armor.getType().isAir()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Пересчитывает, кто спрятан, и синхронизирует таб. */
    private void refresh() {
        hidden.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (shouldHide(player)) {
                hidden.add(player.getUniqueId());
            }
        }
        boolean hideTab = option("hide-in-tab");
        boolean staffSee = option("staff-see-hidden");
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean staff = staffSee && viewer.hasPermission("smpcore.seehidden");
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target == viewer || !viewer.canSee(target)) {
                    continue;
                }
                boolean list = !(hideTab && !staff && hidden.contains(target.getUniqueId()));
                if (viewer.isListed(target) != list) {
                    if (list) {
                        viewer.listPlayer(target);
                    } else {
                        viewer.unlistPlayer(target);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, this::refresh);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hidden.remove(event.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- смерть

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Component message = event.deathMessage();
        if (message == null || !option("hide-in-death-messages")) {
            return;
        }
        List<Player> masked = new ArrayList<>();
        Player victim = event.getEntity();
        if (isHidden(victim) || shouldHide(victim)) {
            masked.add(victim);
        }
        Player killer = victim.getKiller();
        if (killer != null && killer != victim && (isHidden(killer) || shouldHide(killer))) {
            masked.add(killer);
        }
        if (masked.isEmpty()) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (Player player : masked) {
            names.add(player.getName());
            names.add(Text.plain(player.displayName()));
        }
        event.deathMessage(null);
        List<CommandSender> receivers = new ArrayList<>(Bukkit.getOnlinePlayers());
        receivers.add(Bukkit.getConsoleSender());
        for (CommandSender receiver : receivers) {
            Component replacement = plugin.lang().get(receiver, "invisibility.hidden-name");
            receiver.sendMessage(mask(message, names, replacement));
        }
    }

    /** Заменяет ники на заглушку и убирает подсказки/клики, по которым ник можно узнать. */
    private static Component mask(Component component, Set<String> names, Component replacement) {
        Component result = component.hoverEvent(null).clickEvent(null).insertion(null);
        if (result instanceof TextComponent text && names.contains(text.content())) {
            result = replacement.children(text.children());
        }
        if (result instanceof TranslatableComponent translatable) {
            List<Component> args = new ArrayList<>();
            for (TranslationArgument argument : translatable.arguments()) {
                args.add(mask(argument.asComponent(), names, replacement));
            }
            result = translatable.arguments(args);
        }
        List<Component> children = new ArrayList<>();
        for (Component child : result.children()) {
            children.add(mask(child, names, replacement));
        }
        result = result.children(children);
        for (String name : names) {
            result = result.replaceText(builder -> builder.matchLiteral(name).replacement(replacement));
        }
        return result;
    }
}
