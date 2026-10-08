package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Админские приколы: /bolt <ник> — молния в игрока; /svo <ник> — в 20 блоках появляется
 * пчела вчетверо быстрее обычной с крипером верхом, крипер взрывается сразу, как долетит.
 */
public final class AdminFun implements TabExecutor {

    private static final double RADIUS = 20;
    /** Крипер взрывается на этом расстоянии до игрока. */
    private static final double BLAST_DISTANCE = 2.5;
    /** Через сколько тиков дрон сам исчезает, если не долетел. */
    private static final int LIFETIME = 20 * 60;
    /** Во сколько раз пчела быстрее обычной. */
    private static final double SPEED = 4;

    private final SmpCore plugin;

    public AdminFun(SmpCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Component.text("/" + label + " <ник>", NamedTextColor.WHITE));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Игрок не в сети.", NamedTextColor.RED));
            return true;
        }
        if (command.getName().equals("bolt")) {
            target.getWorld().strikeLightning(target.getLocation());
            sender.sendMessage(Component.text("Молния в " + target.getName(), NamedTextColor.GRAY));
        } else {
            launch(target);
            sender.sendMessage(Component.text("Дрон летит к " + target.getName(), NamedTextColor.GRAY));
        }
        return true;
    }

    private void launch(Player target) {
        Location at = spawnPoint(target);
        Bee bee = target.getWorld().spawn(at, Bee.class, b -> {
            b.setPersistent(false);
            b.setRemoveWhenFarAway(false);
            b.setCannotEnterHiveTicks(Integer.MAX_VALUE);
            b.setHasStung(false);
            twice(b.getAttribute(Attribute.FLYING_SPEED));
            twice(b.getAttribute(Attribute.MOVEMENT_SPEED));
        });
        Creeper creeper = target.getWorld().spawn(at, Creeper.class, c -> {
            c.setPersistent(false);
            c.setRemoveWhenFarAway(false);
            c.setAI(false);
        });
        bee.addPassenger(creeper);
        BukkitTask[] task = new BukkitTask[1];
        int[] age = {0};
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            age[0]++;
            if (!creeper.isValid() || !bee.isValid() || !target.isOnline() || target.getWorld() != bee.getWorld()
                    || age[0] > LIFETIME) {
                if (bee.isValid()) {
                    bee.remove();
                }
                if (creeper.isValid()) {
                    creeper.remove();
                }
                task[0].cancel();
                return;
            }
            if (creeper.getLocation().distance(target.getLocation()) <= BLAST_DISTANCE
                    || bee.getLocation().distance(target.getLocation()) <= BLAST_DISTANCE) {
                creeper.explode();
                bee.remove();
                task[0].cancel();
                return;
            }
            if (age[0] % 5 == 0) {
                bee.setAnger(LIFETIME);
                bee.setTarget(target);
                bee.getPathfinder().moveTo(target, 1.5);
            }
        }, 1L, 1L);
    }

    /** Точка в 20 блоках вокруг игрока, на его высоте или над землёй. */
    private static Location spawnPoint(Player target) {
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Location at = target.getLocation().add(Math.cos(angle) * RADIUS, 3, Math.sin(angle) * RADIUS);
        if (!at.getBlock().isPassable() || !at.clone().add(0, 1, 0).getBlock().isPassable()) {
            at.setY(at.getWorld().getHighestBlockYAt(at) + 2);
        }
        return at;
    }

    private static void twice(AttributeInstance attribute) {
        if (attribute != null) {
            attribute.setBaseValue(attribute.getBaseValue() * SPEED);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase();
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(prefix)).toList();
    }
}
