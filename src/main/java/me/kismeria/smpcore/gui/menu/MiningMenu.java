package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Durations;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class MiningMenu extends Menu {

    public MiningMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Добыча";
    }

    @Override
    public boolean live() {
        return true;
    }

    @Override
    protected void draw() {
        set(4, Icon.of(Material.DEEPSLATE_DIAMOND_ORE).name("<blue><bold>Добыча после старта")
                .lore("Глубинные руды: " + plugin.mining().status(viewer),
                        "Незерит: " + plugin.mining().netheriteStatus(viewer),
                        "",
                        "Креатив не ограничен.").build());

        // --- глубинные руды
        toggle(10, Material.DEEPSLATE_DIAMOND_ORE, "Блок глубинных руд",
                List.of("Все deepslate_*_ore нельзя", "копать после старта.", "Взрывы их тоже не ломают."),
                plugin.mining().enabled(), v -> plugin.set("mining.deepslate-lock", v));
        number(11, Material.CLOCK, "Сколько длится", List.of("Сколько ждать после старта."),
                plugin.mining().minutes(), 5, 30, 1, 1440, v -> Durations.human(v * 60L), v -> plugin.set("mining.deepslate-lock-minutes", v));

        List<String> blocks = new ArrayList<>(List.of("Пока руды закрыты, эти блоки", "копаются медленнее:"));
        for (String block : plugin.getConfig().getStringList("mining.slow-dig-blocks")) {
            blocks.add("<white>• " + block.toLowerCase());
        }
        blocks.add("Список — в config.yml.");
        toggle(13, Material.DEEPSLATE, "Медленный глубинный камень", blocks,
                plugin.mining().slowDigEnabled(), v -> plugin.set("mining.slow-dig", v));
        number(14, Material.IRON_PICKAXE, "Во сколько раз медленнее", List.of("Над хотбаром игрок видит", "короткую подсказку."),
                plugin.mining().slowDigFactor(), 1, 1, 2, 10, v -> v + "×", v -> plugin.set("mining.slow-dig-factor", v));

        // --- незерит
        toggle(16, Material.ANCIENT_DEBRIS, "Блок незерита",
                List.of("Древние обломки не копаются,", "улучшение до незерита", "в кузнечном столе не работает."),
                plugin.mining().netheriteEnabled(), v -> plugin.set("mining.netherite-lock", v));
        number(25, Material.CLOCK, "Незерит откроется через", List.of("После старта SMP."),
                plugin.mining().netheriteMinutes(), 60, 720, 1, 60 * 24 * 30, v -> Durations.human(v * 60L),
                v -> plugin.set("mining.netherite-lock-minutes", v));

        back(() -> new MainMenu(plugin, viewer));
    }
}
