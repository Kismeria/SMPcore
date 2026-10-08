package me.kismeria.smpcore.feature;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Баблы с сообщениями над головой (как в FlectonePulse): TextDisplay сидит на игроке как пассажир,
 * поэтому двигается вместе с ним без задержки. Последние несколько сообщений — столбиком,
 * каждое исчезает через своё время.
 */
public final class ChatBubbles implements Listener {

    private record Line(Component text, long until) {
    }

    private static final class Bubble {
        private final Deque<Line> lines = new ArrayDeque<>();
        private TextDisplay display;
    }

    private final SmpCore plugin;
    private final Map<UUID, Bubble> bubbles = new HashMap<>();

    public ChatBubbles(SmpCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.flag("bubbles.enabled")) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        String text = Text.plain(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                add(player, text);
            }
        });
    }

    private void add(Player player, String text) {
        if (!canShow(player) || text.isBlank()) {
            return;
        }
        int maxLength = Math.max(8, plugin.getConfig().getInt("bubbles.max-length", 100));
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength - 1) + "…";
        }
        String format = plugin.getConfig().getString("bubbles.format", "<white><message>");
        long seconds = Math.max(1, plugin.getConfig().getInt("bubbles.seconds", 5));
        // длинное сообщение висит дольше: +1 с за каждые 20 символов
        long until = System.currentTimeMillis() + (seconds + text.length() / 20) * 1000L;

        Bubble bubble = bubbles.computeIfAbsent(player.getUniqueId(), k -> new Bubble());
        bubble.lines.addLast(new Line(Text.mm(format, Lang.txt("message", text)), until));
        int max = Math.max(1, plugin.getConfig().getInt("bubbles.max-messages", 3));
        while (bubble.lines.size() > max) {
            bubble.lines.removeFirst();
        }
        render(player, bubble);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Bubble> entry : Map.copyOf(bubbles).entrySet()) {
            Bubble bubble = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            boolean changed = bubble.lines.removeIf(line -> line.until() <= now);
            if (player == null || bubble.lines.isEmpty() || !canShow(player) || !plugin.flag("bubbles.enabled")) {
                clear(entry.getKey());
                continue;
            }
            boolean riding = bubble.display != null && bubble.display.isValid()
                    && player.getPassengers().contains(bubble.display);
            if (changed || !riding) {
                render(player, bubble);
            }
        }
    }

    private void render(Player player, Bubble bubble) {
        Component text = Component.join(JoinConfiguration.newlines(), bubble.lines.stream().map(Line::text).toList());
        TextDisplay display = bubble.display;
        if (display == null || !display.isValid() || display.getWorld() != player.getWorld()
                || !player.getPassengers().contains(display)) {
            if (display != null) {
                display.remove();
            }
            display = spawn(player);
            bubble.display = display;
        }
        display.text(text);
    }

    private TextDisplay spawn(Player player) {
        float height = (float) plugin.getConfig().getDouble("bubbles.height", 0.35);
        TextDisplay display = player.getWorld().spawn(player.getLocation().add(0, player.getHeight(), 0), TextDisplay.class, created -> {
            created.setPersistent(false);
            created.setBillboard(Display.Billboard.CENTER);
            created.setBackgroundColor(background());
            created.setLineWidth(Math.max(40, plugin.getConfig().getInt("bubbles.line-width", 150)));
            created.setShadowed(true);
            created.setSeeThrough(false);
            created.setAlignment(TextDisplay.TextAlignment.CENTER);
            created.setTransformation(new Transformation(new Vector3f(0, height, 0), new AxisAngle4f(),
                    new Vector3f(1, 1, 1), new AxisAngle4f()));
        });
        if (!plugin.flag("bubbles.show-self")) {
            player.hideEntity(plugin, display);
        }
        player.addPassenger(display);
        return display;
    }

    private Color background() {
        String hex = plugin.getConfig().getString("bubbles.background", "#40000000").replace("#", "");
        try {
            long value = Long.parseLong(hex, 16);
            return hex.length() <= 6 ? Color.fromARGB((int) (0xFF000000L | value)) : Color.fromARGB((int) value);
        } catch (NumberFormatException e) {
            return Color.fromARGB(0x40000000);
        }
    }

    private boolean canShow(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR || plugin.vanish().isVanished(player) || player.isDead()) {
            return false;
        }
        boolean invisible = player.isInvisible() || player.hasPotionEffect(PotionEffectType.INVISIBILITY);
        return !(invisible && plugin.flag("bubbles.hide-when-invisible"));
    }

    private void clear(UUID id) {
        Bubble bubble = bubbles.remove(id);
        if (bubble != null && bubble.display != null) {
            bubble.display.remove();
        }
    }

    /** Убрать бабл, но сообщения оставить — он появится снова на следующем тике (после телепорта и т.п.). */
    private void detach(Player player) {
        Bubble bubble = bubbles.get(player.getUniqueId());
        if (bubble != null && bubble.display != null) {
            bubble.display.remove();
            bubble.display = null;
        }
    }

    // пассажира на игроке лучше снять перед сменой мира — пересадим уже в новом
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo().getWorld() != event.getFrom().getWorld()) {
            detach(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        detach(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    public void disable() {
        for (UUID id : Map.copyOf(bubbles).keySet()) {
            clear(id);
        }
    }
}
