package me.kismeria.smpcore;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Состояние SMP, которое переживает рестарты (data.yml). */
public final class StateStore {

    public enum Phase {
        /** Обычный режим, плагин не трогает границу и лобби. */
        IDLE,
        /** Лобби до старта: малая граница, защита лобби, таймер. */
        LOBBY,
        /** SMP идёт. */
        RUNNING
    }

    private final SmpCore plugin;
    private final File file;

    private Phase phase = Phase.IDLE;
    private long scheduledAt;
    private long startedAt;
    private boolean deepslateAnnounced;
    private boolean netheriteAnnounced;
    private boolean netherAnnounced;
    private boolean endAnnounced;
    private int growthsDone;
    private final Set<UUID> vanished = new HashSet<>();

    public StateStore(SmpCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try {
            phase = Phase.valueOf(yaml.getString("phase", "IDLE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            phase = Phase.IDLE;
        }
        scheduledAt = yaml.getLong("scheduled-at", 0L);
        startedAt = yaml.getLong("started-at", 0L);
        deepslateAnnounced = yaml.getBoolean("deepslate-announced", false);
        netheriteAnnounced = yaml.getBoolean("netherite-announced", false);
        netherAnnounced = yaml.getBoolean("nether-announced", false);
        endAnnounced = yaml.getBoolean("end-announced", false);
        growthsDone = yaml.getInt("growths-done", 0);
        vanished.clear();
        for (String id : yaml.getStringList("vanished")) {
            try {
                vanished.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // битая запись — пропускаем
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(java.util.List.of("Состояние SmpCore. Не редактируй руками, пока сервер запущен."));
        yaml.set("phase", phase.name());
        yaml.set("scheduled-at", scheduledAt);
        yaml.set("started-at", startedAt);
        yaml.set("deepslate-announced", deepslateAnnounced);
        yaml.set("netherite-announced", netheriteAnnounced);
        yaml.set("nether-announced", netherAnnounced);
        yaml.set("end-announced", endAnnounced);
        yaml.set("growths-done", growthsDone);
        yaml.set("vanished", vanished.stream().map(UUID::toString).toList());
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    public Phase phase() {
        return phase;
    }

    public void phase(Phase phase) {
        this.phase = phase;
    }

    public long scheduledAt() {
        return scheduledAt;
    }

    public void scheduledAt(long scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public long startedAt() {
        return startedAt;
    }

    public void startedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public boolean deepslateAnnounced() {
        return deepslateAnnounced;
    }

    public void deepslateAnnounced(boolean deepslateAnnounced) {
        this.deepslateAnnounced = deepslateAnnounced;
    }

    public boolean netheriteAnnounced() {
        return netheriteAnnounced;
    }

    public void netheriteAnnounced(boolean value) {
        this.netheriteAnnounced = value;
    }

    public boolean netherAnnounced() {
        return netherAnnounced;
    }

    public void netherAnnounced(boolean value) {
        this.netherAnnounced = value;
    }

    public boolean endAnnounced() {
        return endAnnounced;
    }

    public void endAnnounced(boolean value) {
        this.endAnnounced = value;
    }

    public int growthsDone() {
        return growthsDone;
    }

    public void growthsDone(int value) {
        this.growthsDone = value;
    }

    /** Игроки в ванише (переживает перезаход и рестарт). */
    public Set<UUID> vanished() {
        return vanished;
    }

    /** Сбросить все одноразовые объявления и счётчики сезона. */
    public void resetSeason() {
        deepslateAnnounced = false;
        netheriteAnnounced = false;
        netherAnnounced = false;
        endAnnounced = false;
        growthsDone = 0;
    }
}
