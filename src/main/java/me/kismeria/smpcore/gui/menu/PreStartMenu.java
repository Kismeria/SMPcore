package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Durations;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class PreStartMenu extends Menu {

    public PreStartMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Перед стартом";
    }

    @Override
    protected void draw() {
        set(4, Icon.of(Material.OAK_SIGN).name("<yellow><bold>Перед стартом")
                .lore("Что происходит в последние", "секунды таймера.").build());

        flag(10, Material.OAK_SIGN, "Отсчёт внизу экрана", List.of("Цифры обратного отсчёта", "над хотбаром у всех."), "start.countdown");
        flag(11, Material.NOTE_BLOCK, "Тиканье", List.of("Звук на последних", "10 секундах."), "start.countdown-sound");
        flag(12, Material.FIREWORK_ROCKET, "Фейерверки", List.of("Салют в центре", "в момент старта."), "start.fireworks");

        flag(14, Material.ARMOR_STAND, "Рассадка по кругу",
                List.of("За N секунд до старта всех", "игроков ставит по кругу", "лицом к центру."), "start.spread.enabled");
        number(15, Material.CLOCK, "Когда рассадить", List.of("За сколько секунд до старта."),
                plugin.getConfig().getInt("start.spread.seconds-before", 10), 1, 10, 1, 300, Durations::human,
                v -> plugin.set("start.spread.seconds-before", v));
        number(16, Material.COMPASS, "Радиус круга", List.of("Не больше половины лобби."),
                plugin.getConfig().getInt("start.spread.radius", 16), 1, 5, 1, 1000, v -> v + " бл.",
                v -> plugin.set("start.spread.radius", v));
        flag(24, Material.PACKED_ICE, "Заморозка",
                List.of("Рассаженные не могут", "сойти с места до старта.", "Крутить головой можно."), "start.spread.freeze");

        back(() -> new StartMenu(plugin, viewer));
    }

    private void flag(int slot, Material material, String name, List<String> description, String path) {
        toggle(slot, material, name, description, plugin.flag(path), v -> plugin.set(path, v));
    }
}
