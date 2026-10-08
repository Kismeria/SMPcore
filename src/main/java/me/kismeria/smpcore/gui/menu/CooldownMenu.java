package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.feature.CooldownType;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Durations;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class CooldownMenu extends Menu {

    /** Верхний ряд — переключатели, под каждым — секунды. */
    private static final int[] TOGGLE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 30, 31, 32};

    public CooldownMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · PvP кулдауны";
    }

    @Override
    protected void draw() {
        set(4, Icon.of(Material.GOLDEN_APPLE).name("<gold><bold>PvP кулдауны")
                .lore("Верхний предмет — вкл/выкл,", "часы под ним — длительность.", "", "Кд сохраняется при перезаходе.").build());

        CooldownType[] types = CooldownType.values();
        for (int i = 0; i < types.length && i < TOGGLE_SLOTS.length; i++) {
            CooldownType type = types[i];
            int slot = TOGGLE_SLOTS[i];
            toggle(slot, type.icon(), type.title(), List.of(type.description()),
                    plugin.cooldowns().enabled(type), v -> plugin.cooldowns().setEnabled(type, v));
            number(slot + 9, Material.CLOCK, type.title() + ": кд", List.of(),
                    plugin.cooldowns().seconds(type), 1, 10, 1, 600, Durations::human, v -> plugin.cooldowns().setSeconds(type, v));
        }

        back(() -> new MainMenu(plugin, viewer));
    }
}
