package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.api.HudProvider;
import me.kismeria.smpcore.util.Bedrock;
import me.kismeria.smpcore.util.FontWidths;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One action bar for everyone: messages (ours and other plugins' via the API) go above the hearts,
 * a provider's meter goes above the hunger bar, composed into one line with space padding.
 * Without a meter a message is centred exactly like vanilla.
 */
public final class ActionBarHud implements Listener {

    /** Vanilla shows an action bar for 60 ticks; resend before it starts fading. */
    private static final int KEEPALIVE = 20;
    /** Gui: food bar spans centre+10..centre+91, hearts centre-91..centre-10. */
    private static final int FOOD_CENTER = 50;
    private static final int HEARTS_CENTER = -50;
    private static final int HEARTS_END = -10;
    private static final int MAX_LEFT = 190;

    private record Message(Component text, long until, String tag, boolean exclusive) {
    }

    private final Map<UUID, Message> messages = new HashMap<>();
    private final Map<UUID, Component> lastSent = new HashMap<>();
    private final Map<UUID, Long> lastAt = new HashMap<>();
    private final Map<Plugin, HudProvider> providers = new LinkedHashMap<>();
    private long ticks;

    public ActionBarHud(SmpCore plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    public void register(Plugin owner, HudProvider provider) {
        providers.put(owner, provider);
    }

    /** Chat-style message, 3 seconds like vanilla. */
    public void message(Player player, Component text) {
        message(player, text, 60, null, false);
    }

    /**
     * @param tag       lets the sender clear its own message later (null = anonymous)
     * @param exclusive hide the meter while this message shows (big centred announcements)
     */
    public void message(Player player, Component text, int ticksToShow, String tag, boolean exclusive) {
        messages.put(player.getUniqueId(), new Message(text, ticks + ticksToShow, tag, exclusive));
        push(player);
    }

    /** Removes the current message if it was sent with this tag. */
    public void clear(Player player, String tag) {
        Message current = messages.get(player.getUniqueId());
        if (current != null && tag.equals(current.tag())) {
            messages.remove(player.getUniqueId());
            push(player);
        }
    }

    private void tick() {
        ticks += 2;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Component line = compose(player);
            UUID id = player.getUniqueId();
            Component last = lastSent.get(id);
            boolean changed = !line.equals(last);
            boolean stale = !line.equals(Component.empty()) && ticks - lastAt.getOrDefault(id, 0L) >= KEEPALIVE;
            if (changed && line.equals(Component.empty()) && last == null) {
                continue;
            }
            if (changed || stale) {
                send(player, line);
            }
        }
    }

    private void push(Player player) {
        send(player, compose(player));
    }

    private void send(Player player, Component line) {
        player.sendActionBar(line);
        lastSent.put(player.getUniqueId(), line);
        lastAt.put(player.getUniqueId(), ticks);
    }

    private Component compose(Player player) {
        Message message = messages.get(player.getUniqueId());
        if (message != null && message.until() <= ticks) {
            messages.remove(player.getUniqueId());
            message = null;
        }
        Component bar = null;
        Component hint = null;
        for (HudProvider provider : providers.values()) {
            try {
                bar = provider.bar(player);
                hint = provider.hint(player);
            } catch (RuntimeException ignored) {
                bar = null;
                hint = null;
            }
            if (bar != null || hint != null) {
                break;
            }
        }
        if (message != null && message.exclusive()) {
            return message.text();
        }
        Component left = message != null ? message.text() : hint;
        if (bar == null) {
            return left != null ? left : Component.empty();
        }
        if (Bedrock.is(player.getUniqueId())) {
            return left != null ? left : bar;
        }
        int leftWidth = left == null ? 0 : FontWidths.width(left);
        if (leftWidth > MAX_LEFT) {
            return left;
        }
        Component laid = layout(left, leftWidth, bar, FontWidths.width(bar));
        return laid != null ? laid : (left != null ? left : bar);
    }

    // ---------------------------------------------------------------- layout

    /**
     * Vanilla draws the action bar centred: x = -width/2. With symmetric padding the line spans
     * -M..M, so every piece lands where we want it. Padding is built from spaces (4 px) and bold
     * spaces (5 px), which can make any width except 1-3, 6, 7 and 11; small nudges fix those.
     */
    static Component layout(Component left, int leftWidth, Component bar, int barWidth) {
        int barStart = FOOD_CENTER - barWidth / 2;
        int leftStart;
        if (left == null) {
            leftStart = barStart;
            leftWidth = 0;
        } else {
            leftStart = leftWidth <= 80 ? HEARTS_CENTER - leftWidth / 2 : HEARTS_END - leftWidth;
        }
        int m0 = Math.max(-leftStart, barStart + barWidth);
        int[] nudges = {0, -1, 1, -2, 2};
        for (int m = m0; m < m0 + 16; m++) {
            for (int dl : nudges) {
                for (int dr : nudges) {
                    int padLeft = leftStart + dl + m;
                    int gap = barStart + dr - (leftStart + dl + leftWidth);
                    int padRight = m - (barStart + dr + barWidth);
                    if (fits(padLeft) && fits(gap) && fits(padRight)) {
                        Component line = Component.empty().append(pad(padLeft));
                        if (left != null) {
                            line = line.append(left);
                        }
                        return line.append(pad(gap)).append(bar).append(pad(padRight));
                    }
                }
            }
        }
        return null;
    }

    private static boolean fits(int px) {
        if (px < 0) {
            return false;
        }
        for (int bold = 0; bold * 5 <= px; bold++) {
            if ((px - bold * 5) % 4 == 0) {
                return true;
            }
        }
        return false;
    }

    private static Component pad(int px) {
        for (int bold = 0; bold * 5 <= px; bold++) {
            if ((px - bold * 5) % 4 == 0) {
                Component plain = Component.text(" ".repeat((px - bold * 5) / 4)).decoration(TextDecoration.BOLD, false);
                Component wide = Component.text(" ".repeat(bold)).decoration(TextDecoration.BOLD, true);
                return Component.empty().append(plain).append(wide);
            }
        }
        return Component.empty();
    }

    // ---------------------------------------------------------------- cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        messages.remove(id);
        lastSent.remove(id);
        lastAt.remove(id);
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        providers.remove(event.getPlugin());
    }
}
