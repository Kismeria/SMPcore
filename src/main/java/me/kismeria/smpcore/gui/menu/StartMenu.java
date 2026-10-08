package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.StateStore.Phase;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Durations;
import me.kismeria.smpcore.util.Icon;
import me.kismeria.smpcore.Lang;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class StartMenu extends Menu {

    public StartMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Старт";
    }

    @Override
    public boolean live() {
        return true;
    }

    @Override
    protected Menu reopen() {
        return new StartMenu(plugin, viewer);
    }

    @Override
    protected void draw() {
        Phase phase = plugin.start().phase();
        set(4, Icon.of(Material.CLOCK).name("<gold><bold>Статус").lore(plugin.start().statusLines()).build());

        boolean running = phase == Phase.RUNNING;
        if (!running) {
            shift(19, Material.RED_STAINED_GLASS_PANE, "<red>−1 час", -3600);
            shift(20, Material.RED_STAINED_GLASS_PANE, "<red>−15 минут", -900);
            shift(21, Material.RED_STAINED_GLASS_PANE, "<red>−1 минута", -60);
            button(22, Icon.of(Material.WRITABLE_BOOK).name("<yellow><bold>Ввести время старта")
                    .lore("Форматы:",
                            "<white>18:30</white> — сегодня (или завтра)",
                            "<white>06.10 18:30</white> — дата и время",
                            "<white>30m</white>, <white>1h30m</white>, <white>2ч</white> — через сколько",
                            "",
                            "Часовой пояс: <white>" + plugin.zone().getId()).build(), t -> askTime());
            shift(23, Material.LIME_STAINED_GLASS_PANE, "<green>+1 минута", 60);
            shift(24, Material.LIME_STAINED_GLASS_PANE, "<green>+15 минут", 900);
            shift(25, Material.LIME_STAINED_GLASS_PANE, "<green>+1 час", 3600);

            button(29, Icon.of(Material.BELL).name("<yellow><bold>Режим лобби")
                    .lore("Сжать границу до размера лобби", "и включить ограничения лобби.", "",
                            phase == Phase.LOBBY ? "<green>Уже включён" : "<yellow>Клик</yellow> — включить").glow(phase == Phase.LOBBY).build(),
                    t -> {
                        if (phase != Phase.LOBBY) {
                            plugin.start().prepareLobby();
                        }
                        redraw();
                    });
            button(31, Icon.of(Material.FIREWORK_ROCKET).name("<green><bold>Старт сейчас")
                    .lore("Запустить SMP немедленно:", "анимация границы, тайтл, фейерверки.").build(),
                    t -> confirm("Запустить SMP прямо сейчас?", () -> plugin.start().startNow()));
            button(33, Icon.of(Material.BARRIER).name("<red><bold>Отменить таймер")
                    .lore("Лобби останется, таймер исчезнет.").build(), t -> {
                plugin.start().cancelSchedule();
                redraw();
            });
        } else {
            set(22, Icon.of(Material.LIME_CONCRETE).name("<green><bold>SMP уже идёт")
                    .lore("Таймер недоступен.", "Чтобы начать заново — «Сбросить SMP».").build());
        }

        button(37, Icon.of(Material.COMPASS).name("<aqua><bold>Граница мира")
                .lore("Размер лобби и финальный,", "длительность анимации, центр.").build(), t -> new BorderMenu(plugin, viewer).open());
        button(38, Icon.of(Material.BELL).name("<yellow><bold>Лобби до старта")
                .lore("Что запрещено, пока", "SMP не начался.").build(), t -> new LobbyMenu(plugin, viewer).open());
        button(39, Icon.of(Material.OAK_SIGN).name("<yellow><bold>Перед стартом")
                .lore("Отсчёт внизу экрана, звук,", "рассадка по кругу, заморозка,", "фейерверки.").build(),
                t -> new PreStartMenu(plugin, viewer).open());
        button(41, Icon.of(Material.ENDER_PEARL).name("<aqua><bold>Собрать всех в центр")
                .lore("Телепорт всех игроков", "в центр границы.").build(),
                t -> confirm("Телепортировать всех в центр?", () -> plugin.start().gatherAll()));
        button(43, Icon.of(Material.TNT).name("<red><bold>Сбросить SMP")
                .lore("Фаза → обычный режим.", "Граница сразу станет финальной,", "таймер блока руд сбросится.").build(),
                t -> confirm("Сбросить состояние SMP?", () -> plugin.start().reset()));

        back(() -> new MainMenu(plugin, viewer));
    }

    private void shift(int slot, Material material, String name, long seconds) {
        button(slot, Icon.of(material).name(name).lore("Сдвинуть время старта.", "Без таймера — от текущего момента.").build(), t -> {
            plugin.start().shift(seconds);
            redraw();
        });
    }

    private void askTime() {
        plugin.prompt().ask(viewer, "prompt.start-time", input -> {
            long at = Durations.parseMoment(input, plugin.zone(), System.currentTimeMillis());
            if (at <= System.currentTimeMillis()) {
                plugin.lang().send(viewer, "command.unknown-time", Lang.txt("input", input));
            } else if (!plugin.start().schedule(at)) {
                plugin.lang().send(viewer, "command.already-running");
            }
            new StartMenu(plugin, viewer).open();
        });
    }
}
