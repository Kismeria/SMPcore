package me.kismeria.smpcore.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Ввод значения через чат (время старта, MOTD и т.п.). */
public final class ChatPrompt implements Listener {

    private record Pending(Consumer<String> handler, long expiresAt) {
    }

    private final SmpCore plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(SmpCore plugin) {
        this.plugin = plugin;
    }

    /** @param questionKey ключ вопроса в lang */
    public void ask(Player player, String questionKey, Consumer<String> handler) {
        player.closeInventory();
        pending.put(player.getUniqueId(), new Pending(handler, System.currentTimeMillis() + 120_000L));
        plugin.lang().send(player, questionKey);
        plugin.lang().send(player, "prompt.hint");
    }

    public boolean waiting(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending entry = pending.remove(player.getUniqueId());
        if (entry == null || entry.expiresAt() < System.currentTimeMillis()) {
            return;
        }
        event.setCancelled(true);
        String message = Text.plain(event.message()).trim();
        boolean cancel = Arrays.asList(plugin.lang().raw(player, "prompt.cancel-words").toLowerCase(Locale.ROOT).split(" "))
                .contains(message.toLowerCase(Locale.ROOT));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (cancel) {
                plugin.lang().send(player, "prompt.cancelled");
                return;
            }
            entry.handler().accept(message);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
