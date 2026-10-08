package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.feature.DimensionControl.Dim;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Durations;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

public final class WorldMenu extends Menu {

    public WorldMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Мир";
    }

    @Override
    protected void draw() {
        set(4, Icon.of(Material.GRASS_BLOCK).name("<green><bold>Мир")
                .lore("Правила игры меняются", "сразу во всех мирах.").build());

        toggle(10, Material.NETHERRACK, "Ад", List.of("Порталы в ад работают.", "Выключение выкинет игроков", "из ада в обычный мир."),
                plugin.dimensions().enabledFlag(Dim.NETHER), v -> plugin.dimensions().setEnabled(Dim.NETHER, v));
        toggle(11, Material.END_STONE, "Энд", List.of("Порталы в энд и око эндера", "в рамке работают.", "Выключение выкинет игроков из энда."),
                plugin.dimensions().enabledFlag(Dim.END), v -> plugin.dimensions().setEnabled(Dim.END, v));
        number(28, Material.CRYING_OBSIDIAN, "Ад откроется через", List.of("Минут после старта SMP.", "0 — сразу. За 10 и 1 минуту", "в чате будет предупреждение."),
                plugin.dimensions().openAfterMinutes(Dim.NETHER), 5, 60, 0, 60 * 24 * 30, v -> v == 0 ? "сразу" : Durations.human(v * 60L),
                v -> plugin.set("world.nether-open-after-minutes", v));
        number(29, Material.ENDER_EYE, "Энд откроется через", List.of("Минут после старта SMP.", "0 — сразу."),
                plugin.dimensions().openAfterMinutes(Dim.END), 5, 60, 0, 60 * 24 * 30, v -> v == 0 ? "сразу" : Durations.human(v * 60L),
                v -> plugin.set("world.end-open-after-minutes", v));

        rule(13, Material.DIAMOND_SWORD, "PvP", "Игроки могут бить друг друга.", GameRules.PVP);
        toggle(14, Material.TNT_MINECART, "TNT ломает блоки",
                List.of("Выкл — TNT и вагонетки с TNT", "взрываются, но блоки целы.", "",
                        "<dark_gray>keepInventory всегда включён:", "<dark_gray>вещи после смерти — в душе."),
                plugin.flag("world.tnt-block-damage"), v -> plugin.set("world.tnt-block-damage", v));
        rule(15, Material.RECOVERY_COMPASS, "Локатор-бар", "Показывать других игроков", "на полоске опыта.", GameRules.LOCATOR_BAR);
        rule(16, Material.GLISTERING_MELON_SLICE, "Естественная регенерация", "Хп восстанавливается от сытости.", GameRules.NATURAL_HEALTH_REGENERATION);

        rule(19, Material.PHANTOM_MEMBRANE, "Фантомы", "Спавн фантомов без сна.", GameRules.SPAWN_PHANTOMS);
        toggle(20, Material.CREEPER_HEAD, "Грифинг мобов",
                List.of("Криперы, гасты, иссушитель ломают", "блоки, эндермены их берут, зомби", "выбивают двери.",
                        "Не через геймрул: жители всё так же", "собирают урожай и размножаются."),
                plugin.flag("world.mob-griefing"), v -> plugin.set("world.mob-griefing", v));
        rule(21, Material.LEAD, "Странствующий торговец", "Спавн торговца с ламами.", GameRules.SPAWN_WANDERING_TRADERS);
        rule(22, Material.CROSSBOW, "Рейды", "Рейды разбойников.", GameRules.RAIDS);
        rule(23, Material.KNOWLEDGE_BOOK, "Достижения в чат", "Сообщения о достижениях.", GameRules.SHOW_ADVANCEMENT_MESSAGES);
        rule(24, Material.SKELETON_SKULL, "Сообщения о смерти", "Писать в чат о смертях.", GameRules.SHOW_DEATH_MESSAGES);
        rule(25, Material.TNT, "Взрыв TNT", "TNT вообще взрывается.", GameRules.TNT_EXPLODES);

        World main = plugin.mainWorld();
        Difficulty difficulty = main.getDifficulty();
        button(30, Icon.of(Material.ZOMBIE_HEAD).name("<aqua>Сложность")
                .lore("Сейчас: <white>" + difficultyName(difficulty), "", "<yellow>Клик</yellow> — следующая").build(), t -> {
            Difficulty next = Difficulty.values()[(difficulty.ordinal() + 1) % Difficulty.values().length];
            for (World world : Bukkit.getWorlds()) {
                world.setDifficulty(next);
            }
            redraw();
        });
        Integer sleep = main.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        number(32, Material.RED_BED, "Сон: процент игроков", List.of("Сколько игроков должно спать,", "чтобы пропустить ночь."),
                sleep == null ? 100 : sleep, 10, 50, 0, 100, v -> v + "%", v -> setRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, v));

        back(() -> new MainMenu(plugin, viewer));
    }

    private void rule(int slot, Material material, String name, String line, GameRule<Boolean> rule) {
        rule(slot, material, name, List.of(line), rule);
    }

    private void rule(int slot, Material material, String name, String line1, String line2, GameRule<Boolean> rule) {
        rule(slot, material, name, List.of(line1, line2), rule);
    }

    private void rule(int slot, Material material, String name, List<String> description, GameRule<Boolean> rule) {
        Boolean value = plugin.mainWorld().getGameRuleValue(rule);
        toggle(slot, material, name, description, Boolean.TRUE.equals(value), v -> setRule(rule, v));
    }

    private static <T> void setRule(GameRule<T> rule, T value) {
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(rule, value);
        }
    }

    private static String difficultyName(Difficulty difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> "мирная";
            case EASY -> "лёгкая";
            case NORMAL -> "нормальная";
            case HARD -> "сложная";
        };
    }
}
