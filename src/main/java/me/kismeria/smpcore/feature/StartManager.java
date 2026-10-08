package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.StateStore;
import me.kismeria.smpcore.StateStore.Phase;
import me.kismeria.smpcore.util.Durations;
import me.kismeria.smpcore.util.SafeSpot;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Старт SMP: лобби, таймер с обратным отсчётом, рассадка по кругу, анимация расширения границы,
 * плановое расширение границы после старта.
 */
public final class StartManager implements Listener {

    private final SmpCore plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Set<UUID> frozen = new HashSet<>();
    private BukkitTask task;
    private long lastShownSecond = -1;
    private boolean barVisible;
    private boolean spreadDone;

    public StartManager(SmpCore plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
        if (expanding(System.currentTimeMillis())) {
            resumeExpansion();
        }
    }

    public void disable() {
        if (task != null) {
            task.cancel();
        }
        hideBar();
    }

    private StateStore state() {
        return plugin.state();
    }

    private Lang lang() {
        return plugin.lang();
    }

    public Phase phase() {
        return state().phase();
    }

    // ---------------------------------------------------------------- граница

    public int lobbySize() {
        return Math.max(4, plugin.getConfig().getInt("border.lobby-size", 48));
    }

    public int finalSize() {
        return Math.max(16, plugin.getConfig().getInt("border.final-size", 10000));
    }

    public int expandSeconds() {
        return Math.max(0, plugin.getConfig().getInt("border.expand-seconds", 60));
    }

    public boolean growthEnabled() {
        return plugin.flag("border.growth.enabled");
    }

    public long growthIntervalMillis() {
        return Math.max(1, plugin.getConfig().getInt("border.growth.every-hours", 168)) * 3_600_000L;
    }

    /** Размер, до которого граница должна дорасти сейчас (с учётом планового расширения). */
    public int targetSize() {
        int base = finalSize();
        if (!growthEnabled()) {
            return base;
        }
        int max = Math.max(base, plugin.getConfig().getInt("border.growth.max-size", 30000));
        long grown = (long) base + (long) state().growthsDone() * plugin.getConfig().getInt("border.growth.amount", 2000);
        return (int) Math.min(max, grown);
    }

    public long nextGrowthAt() {
        return state().startedAt() + (state().growthsDone() + 1L) * growthIntervalMillis();
    }

    public double centerX() {
        if (plugin.getConfig().isSet("border.center-x")) {
            return plugin.getConfig().getDouble("border.center-x");
        }
        return plugin.mainWorld().getSpawnLocation().getBlockX() + 0.5;
    }

    public double centerZ() {
        if (plugin.getConfig().isSet("border.center-z")) {
            return plugin.getConfig().getDouble("border.center-z");
        }
        return plugin.mainWorld().getSpawnLocation().getBlockZ() + 0.5;
    }

    /** Центр границы + спавн мира. */
    public void setCenter(Location at) {
        plugin.getConfig().set("border.center-x", at.getBlockX() + 0.5);
        plugin.getConfig().set("border.center-z", at.getBlockZ() + 0.5);
        plugin.saveConfig();
        at.getWorld().setSpawnLocation(at);
        if (phase() == Phase.LOBBY) {
            applyBorder(lobbySize(), 0);
        } else {
            WorldBorder border = plugin.mainWorld().getWorldBorder();
            border.setCenter(centerX(), centerZ());
            syncNether(border.getSize(), 0);
        }
    }

    public Location centerLocation() {
        World world = plugin.mainWorld();
        int x = (int) Math.floor(centerX());
        int z = (int) Math.floor(centerZ());
        return SafeSpot.stand(world, x, z, world.getHighestBlockYAt(x, z) + 1, world.getMaxHeight());
    }

    /** Ставит границу основного мира (и ада, если включена синхронизация). */
    public void applyBorder(double size, long seconds) {
        WorldBorder border = plugin.mainWorld().getWorldBorder();
        border.setCenter(centerX(), centerZ());
        if (seconds > 0) {
            border.changeSize(size, seconds * 20L);
        } else {
            border.setSize(size);
        }
        syncNether(size, seconds);
    }

    private void syncNether(double size, long seconds) {
        if (!plugin.flag("border.sync-nether")) {
            return;
        }
        World nether = plugin.worldOf(World.Environment.NETHER);
        if (nether == null) {
            return;
        }
        WorldBorder border = nether.getWorldBorder();
        border.setCenter(centerX() / 8.0, centerZ() / 8.0);
        double netherSize = Math.max(16, size / 8.0);
        if (seconds > 0) {
            border.changeSize(netherSize, seconds * 20L);
        } else {
            border.setSize(netherSize);
        }
    }

    /**
     * Размеры поменяли в меню или конфиге — граница сразу подстраивается:
     * в лобби — под размер лобби, после старта — под текущую цель (кроме анимации старта).
     */
    public void refreshBorder() {
        double size = plugin.mainWorld().getWorldBorder().getSize();
        if (phase() == Phase.LOBBY) {
            if (Math.abs(size - lobbySize()) > 0.5) {
                applyBorder(lobbySize(), 1);
            }
        } else if (phase() == Phase.RUNNING && !expanding(System.currentTimeMillis())) {
            if (Math.abs(size - targetSize()) > 0.5) {
                applyBorder(targetSize(), 3);
            }
        }
    }

    // ---------------------------------------------------------------- фазы

    public void prepareLobby() {
        state().phase(Phase.LOBBY);
        state().save();
        applyBorder(lobbySize(), 0);
        lang().broadcast("start.lobby-ready");
    }

    /** @return false, если SMP уже идёт */
    public boolean schedule(long at) {
        if (phase() == Phase.RUNNING) {
            return false;
        }
        if (phase() == Phase.IDLE) {
            prepareLobby();
        }
        state().scheduledAt(at);
        state().save();
        lastShownSecond = -1;
        unfreeze();
        long seconds = (at - System.currentTimeMillis()) / 1000;
        String time = Durations.clock(at, plugin.zone());
        lang().broadcast("start.scheduled", viewer -> TagResolver.resolver(
                Lang.ph("time", time), Lang.ph("left", lang().duration(viewer, seconds))));
        return true;
    }

    /** Сдвигает таймер. Без таймера — отсчитывает от текущего момента. */
    public boolean shift(long deltaSeconds) {
        long now = System.currentTimeMillis();
        long base = state().scheduledAt() > 0 ? state().scheduledAt() : now;
        long target = Math.max(now + 10_000L, base + deltaSeconds * 1000L);
        return schedule(target);
    }

    public void cancelSchedule() {
        if (state().scheduledAt() == 0) {
            return;
        }
        state().scheduledAt(0);
        state().save();
        unfreeze();
        lang().broadcast("start.cancelled");
    }

    public void startNow() {
        if (phase() == Phase.RUNNING) {
            return;
        }
        long now = System.currentTimeMillis();
        if (phase() == Phase.IDLE) {
            applyBorder(lobbySize(), 0);
        }
        state().phase(Phase.RUNNING);
        state().startedAt(now);
        state().scheduledAt(0);
        state().resetSeason();
        state().save();
        unfreeze();
        plugin.dimensions().resetWarnings();

        applyBorder(finalSize(), expandSeconds());
        celebrate();
        if (expandSeconds() > 0) {
            showBar();
        }
    }

    /** Возврат в обычный режим: граница сразу финальная, таймеры сброшены. */
    public void reset() {
        state().phase(Phase.IDLE);
        state().scheduledAt(0);
        state().startedAt(0);
        state().resetSeason();
        state().save();
        unfreeze();
        applyBorder(finalSize(), 0);
        hideBar();
        Bukkit.getPluginManager().callEvent(new me.kismeria.smpcore.api.SmpResetEvent());
    }

    public void gatherAll() {
        Location center = centerLocation();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR) {
                player.leaveVehicle();
                player.teleport(center);
            }
        }
    }

    // ---------------------------------------------------------------- тик

    private void tick() {
        long now = System.currentTimeMillis();
        if (phase() == Phase.LOBBY && state().scheduledAt() > 0) {
            long remaining = state().scheduledAt() - now;
            if (remaining <= 0) {
                startNow();
                return;
            }
            long seconds = (remaining + 999) / 1000;
            if (seconds != lastShownSecond) {
                lastShownSecond = seconds;
                showCountdown(seconds);
                if (!spreadDone && plugin.flag("start.spread.enabled")
                        && seconds <= Math.max(1, plugin.getConfig().getInt("start.spread.seconds-before", 10))) {
                    spreadDone = true;
                    spreadPlayers(seconds);
                }
            }
        }
        if (phase() == Phase.RUNNING) {
            if (barVisible) {
                updateBar(now);
            }
            checkGrowth(now);
            plugin.mining().checkAnnounce(now);
            plugin.dimensions().checkUnlocks(now);
        }
    }

    private void showCountdown(long seconds) {
        if (!plugin.flag("start.countdown")) {
            return;
        }
        String color = seconds <= 10 ? "<red><bold>" : seconds <= 60 ? "<gold><bold>" : "<white><bold>";
        Component digits = me.kismeria.smpcore.util.Text.mm(color + Durations.digits(seconds));
        boolean sound = seconds <= 10 && plugin.flag("start.countdown-sound");
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(digits);
            if (sound) {
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 0.6f + (10 - seconds) * 0.1f);
            }
        }
    }

    private void celebrate() {
        Title.Times times = Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(lang().get(player, "start.title"), lang().get(player, "start.subtitle"), times));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            player.playSound(player, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.2f);
        }
        lang().broadcast("start.started", Lang.ph("size", finalSize()));
        if (plugin.flag("start.fireworks")) {
            launchFireworks(centerLocation());
        }
    }

    private void launchFireworks(Location center) {
        Color[] colors = {Color.ORANGE, Color.YELLOW, Color.AQUA, Color.LIME, Color.FUCHSIA, Color.WHITE};
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2 * i / 8;
            Location at = center.clone().add(Math.cos(angle) * 6, 1, Math.sin(angle) * 6);
            Firework firework = at.getWorld().spawn(at, Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(i % 2 == 0 ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.STAR)
                    .withColor(colors[i % colors.length])
                    .withFade(Color.WHITE)
                    .trail(true).flicker(true).build());
            meta.setPower(1 + i % 2);
            firework.setFireworkMeta(meta);
        }
    }

    // ---------------------------------------------------------------- рассадка и заморозка

    private void spreadPlayers(long secondsLeft) {
        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            return;
        }
        World world = plugin.mainWorld();
        double cx = centerX();
        double cz = centerZ();
        double radius = Math.max(1, Math.min(plugin.getConfig().getInt("start.spread.radius", 16), lobbySize() / 2.0 - 2));
        boolean freeze = plugin.flag("start.spread.freeze");
        for (int i = 0; i < players.size(); i++) {
            double angle = Math.PI * 2 * i / players.size();
            int x = (int) Math.floor(cx + Math.cos(angle) * radius);
            int z = (int) Math.floor(cz + Math.sin(angle) * radius);
            Location spot = SafeSpot.stand(world, x, z, world.getHighestBlockYAt(x, z) + 1, world.getMaxHeight());
            spot.setDirection(new Vector(cx - spot.getX(), 0, cz - spot.getZ()));
            Player player = players.get(i);
            player.leaveVehicle();
            player.teleport(spot);
            if (freeze) {
                frozen.add(player.getUniqueId());
            }
        }
        lang().broadcast("start.spread", viewer -> Lang.ph("left", lang().duration(viewer, secondsLeft)));
    }

    private void unfreeze() {
        frozen.clear();
        spreadDone = false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (frozen.isEmpty() || !frozen.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            event.setTo(back);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        frozen.remove(event.getPlayer().getUniqueId());
        BossBar bar = bars.remove(event.getPlayer().getUniqueId());
        if (bar != null) {
            event.getPlayer().hideBossBar(bar);
        }
    }

    // ---------------------------------------------------------------- плановое расширение

    private void checkGrowth(long now) {
        if (!growthEnabled() || expanding(now)) {
            return;
        }
        long due = (now - state().startedAt()) / growthIntervalMillis();
        if (due <= state().growthsDone()) {
            return;
        }
        state().growthsDone(state().growthsDone() + 1);
        state().save();
        int target = targetSize();
        if (target <= plugin.mainWorld().getWorldBorder().getSize() + 0.5) {
            return;
        }
        long seconds = Math.max(0, plugin.getConfig().getInt("border.growth.expand-minutes", 10)) * 60L;
        applyBorder(target, seconds);
        lang().broadcast("border.growth", viewer -> TagResolver.resolver(
                Lang.ph("size", target), Lang.ph("left", lang().duration(viewer, seconds))));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f);
        }
    }

    // ---------------------------------------------------------------- боссбар расширения

    public boolean expanding(long now) {
        return phase() == Phase.RUNNING && expandSeconds() > 0 && now < state().startedAt() + expandSeconds() * 1000L;
    }

    private void resumeExpansion() {
        long now = System.currentTimeMillis();
        long total = expandSeconds() * 1000L;
        long elapsed = now - state().startedAt();
        double progress = Math.min(1.0, Math.max(0.0, elapsed / (double) total));
        double size = lobbySize() + (finalSize() - lobbySize()) * progress;
        applyBorder(size, 0);
        long left = Math.max(1, (total - elapsed) / 1000);
        applyBorder(finalSize(), left);
        showBar();
    }

    private void showBar() {
        barVisible = true;
        updateBar(System.currentTimeMillis());
    }

    private void hideBar() {
        barVisible = false;
        for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.hideBossBar(entry.getValue());
            }
        }
        bars.clear();
    }

    private void updateBar(long now) {
        if (!expanding(now)) {
            hideBar();
            return;
        }
        double size = plugin.mainWorld().getWorldBorder().getSize();
        float progress = (float) ((size - lobbySize()) / Math.max(1.0, finalSize() - lobbySize()));
        progress = Math.max(0f, Math.min(1f, progress));
        for (Player player : Bukkit.getOnlinePlayers()) {
            BossBar bar = bars.computeIfAbsent(player.getUniqueId(), id -> {
                BossBar created = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_20);
                player.showBossBar(created);
                return created;
            });
            bar.progress(progress);
            bar.name(lang().get(player, "start.bossbar", Lang.ph("size", (int) size), Lang.ph("final", finalSize())));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (barVisible) {
            updateBar(System.currentTimeMillis());
        }
    }

    // ---------------------------------------------------------------- статус

    /** Короткий статус для таба и MOTD на нужном языке. */
    public String statusText(String locale) {
        long now = System.currentTimeMillis();
        return switch (phase()) {
            case IDLE -> lang().raw(locale, "tab.status-idle");
            case LOBBY -> state().scheduledAt() > 0
                    ? lang().raw(locale, "tab.status-lobby").replace("<left>", Durations.digits((state().scheduledAt() - now) / 1000))
                    : lang().raw(locale, "tab.status-lobby-wait");
            case RUNNING -> lang().raw(locale, "tab.status-running")
                    .replace("<left>", lang().duration(locale, (now - state().startedAt()) / 1000));
        };
    }

    /** Строки для меню админа. */
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        long now = System.currentTimeMillis();
        switch (phase()) {
            case IDLE -> lines.add("Фаза: <white>обычный режим");
            case LOBBY -> {
                lines.add("Фаза: <yellow>лобби до старта");
                if (state().scheduledAt() > 0) {
                    long left = (state().scheduledAt() - now) / 1000;
                    lines.add("Старт: <white>" + Durations.clock(state().scheduledAt(), plugin.zone()));
                    lines.add("Осталось: <gold>" + Durations.digits(left));
                } else {
                    lines.add("Таймер: <red>не задан");
                }
            }
            case RUNNING -> {
                lines.add("Фаза: <green>SMP идёт");
                lines.add("Начался: <white>" + Durations.clock(state().startedAt(), plugin.zone()));
                lines.add("Прошло: <white>" + Durations.human((now - state().startedAt()) / 1000));
                if (expanding(now)) {
                    lines.add("Граница: <green>расширяется");
                } else if (growthEnabled() && targetSize() < plugin.getConfig().getInt("border.growth.max-size", 30000)) {
                    lines.add("Рост границы через: <white>" + Durations.human((nextGrowthAt() - now) / 1000));
                }
            }
        }
        return lines;
    }
}
