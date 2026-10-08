package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ники над головами скрыты (команда основного табло с nametagVisibility=never).
 * Вместо них — ник игрока, на которого смотришь, в action bar.
 */
public final class NameTags implements Listener {

    private static final String TEAM = "smpcore_nametags";
    private static final long PERIOD = 2L;
    /** Раз в сколько тиков повторять action bar, пока смотришь на того же игрока. */
    private static final int REFRESH = 20;

    private final SmpCore plugin;
    private final Map<UUID, UUID> looking = new HashMap<>();
    private int ticks;

    public NameTags(SmpCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, PERIOD);
    }

    // ---------------------------------------------------------------- ники над головой

    /** Включить/выключить скрытие по конфигу (на старте и после reload). */
    public void apply() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(TEAM);
        if (!plugin.flag("nametags.hide")) {
            if (team != null) {
                team.unregister();
            }
            return;
        }
        if (team == null) {
            team = board.registerNewTeam(TEAM);
        }
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        // все в одной команде — иначе игроки видят невидимок полупрозрачными, как союзников
        team.setCanSeeFriendlyInvisibles(false);
        for (Player player : Bukkit.getOnlinePlayers()) {
            join(team, board, player);
        }
    }

    public void disable() {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(TEAM);
        if (team != null) {
            team.unregister();
        }
    }

    private static void join(Team team, Scoreboard board, Player player) {
        // игрока из чужой команды (/team, другой плагин) не трогаем
        if (board.getEntryTeam(player.getName()) == null) {
            team.addEntry(player.getName());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(TEAM);
        if (team != null) {
            join(team, board, event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        looking.remove(id);
        looking.values().remove(id);
    }

    // ---------------------------------------------------------------- ник в action bar

    private void tick() {
        if (!plugin.flag("nametags.look-actionbar")) {
            looking.clear();
            return;
        }
        ticks += (int) PERIOD;
        double distance = Math.max(1, plugin.getConfig().getDouble("nametags.look-distance", 24));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Player target = lookedAt(viewer, distance);
            UUID previous = looking.get(viewer.getUniqueId());
            if (target == null) {
                if (previous != null) {
                    looking.remove(viewer.getUniqueId());
                    plugin.hud().clear(viewer, "nametag");
                }
                continue;
            }
            if (!target.getUniqueId().equals(previous) || ticks % REFRESH == 0) {
                looking.put(viewer.getUniqueId(), target.getUniqueId());
                plugin.hud().message(viewer, plugin.lang().get(viewer, "nametag.looking", Lang.txt("player", target.getName())), REFRESH + 10, "nametag", false);
            }
        }
    }

    private Player lookedAt(Player viewer, double distance) {
        Location eye = viewer.getEyeLocation();
        // стены закрывают: сквозь блоки ник не виден
        RayTraceResult hit = viewer.getWorld().rayTrace(eye, eye.getDirection(), distance,
                FluidCollisionMode.NEVER, true, 0.1, entity -> entity instanceof Player other && other != viewer && visible(viewer, other));
        return hit != null && hit.getHitEntity() instanceof Player target ? target : null;
    }

    private static boolean visible(Player viewer, Player target) {
        return viewer.canSee(target)
                && target.getGameMode() != GameMode.SPECTATOR
                && !target.isInvisible()
                && !target.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }
}
