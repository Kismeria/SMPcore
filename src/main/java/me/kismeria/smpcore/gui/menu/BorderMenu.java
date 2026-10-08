package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.StateStore.Phase;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Durations;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Material;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class BorderMenu extends Menu {

    public BorderMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Граница";
    }

    @Override
    public boolean live() {
        return true;
    }

    @Override
    protected void draw() {
        WorldBorder border = plugin.mainWorld().getWorldBorder();
        set(4, Icon.of(Material.FILLED_MAP).name("<aqua><bold>Граница сейчас")
                .lore("Мир: <white>" + plugin.mainWorld().getName(),
                        "Размер: <white>" + (int) border.getSize(),
                        "Цель: <white>" + plugin.start().targetSize(),
                        "Центр: <white>" + String.format(Locale.ROOT, "%.1f, %.1f", border.getCenter().getX(), border.getCenter().getZ()))
                .build());

        number(10, Material.OAK_FENCE, "Размер лобби", List.of("Сторона квадрата до старта.", "В лобби граница меняется сразу."),
                plugin.start().lobbySize(), 2, 16, 8, 2000, v -> v + " бл.", v -> {
                    plugin.set("border.lobby-size", v);
                    plugin.start().refreshBorder();
                });
        number(11, Material.STRUCTURE_VOID, "Финальный размер", List.of("Сторона квадрата после", "расширения на старте."),
                plugin.start().finalSize(), 500, 5000, 100, 59_999_968, v -> v + " бл.", v -> {
                    plugin.set("border.final-size", v);
                    plugin.start().refreshBorder();
                });
        number(12, Material.CLOCK, "Длительность анимации", List.of("За сколько граница", "расширится на старте.", "0 — мгновенно."),
                plugin.start().expandSeconds(), 5, 60, 0, 3600, Durations::human, v -> plugin.set("border.expand-seconds", v));

        button(14, Icon.of(Material.LODESTONE).name("<yellow><bold>Центр = моя позиция")
                .lore("Центр границы и спавн мира", "переедут к тебе.").build(), t -> {
            if (viewer.getWorld() != plugin.mainWorld()) {
                plugin.lang().send(viewer, "admin.need-main-world");
                return;
            }
            plugin.start().setCenter(viewer.getLocation());
            plugin.lang().send(viewer, "admin.center-moved");
            redraw();
        });
        toggle(15, Material.NETHERRACK, "Синхронизировать ад",
                List.of("Граница ада = размер / 8,", "центр / 8."),
                plugin.flag("border.sync-nether"), v -> plugin.set("border.sync-nether", v));
        button(16, Icon.of(Material.NETHER_STAR).name("<green><bold>Применить сейчас")
                .lore("Лобби — размер лобби,", "иначе — текущая цель,", "мгновенно.").build(), t -> {
            boolean lobby = plugin.start().phase() == Phase.LOBBY;
            plugin.start().applyBorder(lobby ? plugin.start().lobbySize() : plugin.start().targetSize(), 0);
            plugin.lang().send(viewer, "admin.border-applied");
            redraw();
        });

        set(22, Icon.of(Material.SPYGLASS).name("<aqua><bold>Плановое расширение")
                .lore("После старта граница растёт сама:", "+N блоков каждые M часов,", "пока не дойдёт до максимума.").build());
        toggle(28, Material.MAP, "Плановое расширение",
                List.of("Включить рост границы", "по расписанию."),
                plugin.start().growthEnabled(), v -> plugin.set("border.growth.enabled", v));
        number(29, Material.OAK_SAPLING, "Прибавка", List.of("На сколько блоков растёт", "сторона за раз."),
                plugin.getConfig().getInt("border.growth.amount", 2000), 250, 1000, 50, 1_000_000, v -> "+" + v + " бл.",
                v -> plugin.set("border.growth.amount", v));
        number(30, Material.CLOCK, "Как часто", List.of("Раз в сколько часов."),
                plugin.getConfig().getInt("border.growth.every-hours", 168), 1, 24, 1, 24 * 60, v -> Durations.human(v * 3600L),
                v -> plugin.set("border.growth.every-hours", v));
        number(32, Material.BEACON, "Максимум", List.of("Дальше граница не растёт."),
                plugin.getConfig().getInt("border.growth.max-size", 30000), 1000, 10000, 100, 59_999_968, v -> v + " бл.",
                v -> plugin.set("border.growth.max-size", v));
        number(33, Material.COMPASS, "Скорость роста", List.of("За сколько минут граница", "дорастает до нового размера."),
                plugin.getConfig().getInt("border.growth.expand-minutes", 10), 1, 10, 0, 1440, v -> Durations.human(v * 60L),
                v -> plugin.set("border.growth.expand-minutes", v));

        back(() -> new StartMenu(plugin, viewer));
    }
}
