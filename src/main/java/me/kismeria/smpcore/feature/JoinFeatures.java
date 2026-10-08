package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.StateStore.Phase;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Звуки входа/выхода (меняются в /smp) и сообщение о первом заходе с номером игрока. */
public final class JoinFeatures implements Listener {

    private final SmpCore plugin;

    public JoinFeatures(SmpCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        if (plugin.vanish().isVanished(joined)) {
            return;
        }
        play("join", joined);
        if (!joined.hasPlayedBefore() && plugin.flag("features.first-join-message") && firstJoinAllowed()) {
            int number = Bukkit.getOfflinePlayers().length;
            plugin.lang().broadcast("join.first", Lang.txt("player", joined.getName()), Lang.ph("number", number));
            // как в Vanilla Refresh: тихий звук уровня всем
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.4f, 0.5f);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.vanish().isVanished(event.getPlayer())) {
            play("quit", event.getPlayer());
        }
    }

    /** В лобби и первые 30 минут после старта все заходят впервые — не спамим. */
    private boolean firstJoinAllowed() {
        Phase phase = plugin.start().phase();
        if (phase == Phase.LOBBY) {
            return false;
        }
        return phase != Phase.RUNNING || System.currentTimeMillis() - plugin.state().startedAt() >= 30 * 60_000L;
    }

    private void play(String type, Player except) {
        if (!plugin.flag("sounds." + type + ".enabled")) {
            return;
        }
        String sound = plugin.getConfig().getString("sounds." + type + ".sound", "");
        if (sound == null || sound.isBlank()) {
            return;
        }
        float volume = (float) plugin.getConfig().getDouble("sounds." + type + ".volume", 0.6);
        float pitch = (float) plugin.getConfig().getDouble("sounds." + type + ".pitch", 1.0);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != except) {
                player.playSound(player, sound, SoundCategory.MASTER, volume, pitch);
            }
        }
    }

    /** Для меню: проиграть звук только одному игроку. */
    public static void preview(Player player, String sound, float volume, float pitch) {
        player.playSound(player, sound, SoundCategory.MASTER, volume, pitch);
    }
}
