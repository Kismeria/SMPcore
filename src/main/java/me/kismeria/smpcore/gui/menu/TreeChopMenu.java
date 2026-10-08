package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /treechop (админы) и кнопка в /smp: все настройки рубки деревьев. */
public final class TreeChopMenu extends Menu {

    private static final String[] MODES = {"fall", "sequential", "instant"};
    private static final String[] MODE_NAMES = {"Падает целиком", "По одному бревну", "Всё сразу"};
    private static final String[] ACTIVATIONS = {"not-sneaking", "sneaking", "always"};
    private static final String[] ACTIVATION_NAMES = {"Стоя (присел — по бревну)", "Только присев", "Всегда"};

    public TreeChopMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Рубка деревьев";
    }

    private static String key(String name) {
        return "treechop." + name;
    }

    private int num(String name, int def) {
        return plugin.getConfig().getInt(key(name), def);
    }

    private boolean bool(String name, boolean def) {
        return plugin.getConfig().getBoolean(key(name), def);
    }

    @Override
    protected void draw() {
        set(4, Icon.of(Material.IRON_AXE).name("<gold><bold>Рубка деревьев")
                .lore("Срубил бревно топором —", "падает всё дерево. Работает у всех.").build());

        // --- главное
        toggle(10, Material.LIME_DYE, "Включено", List.of("Рубка деревьев целиком", "для всех игроков."),
                bool("enabled", true), v -> plugin.set(key("enabled"), v));
        cycle(11, Material.OAK_SAPLING, "Анимация", "animation", MODES, MODE_NAMES,
                List.of("Падает — дерево валится от игрока", "и рассыпается в дроп.", "По одному — снизу вверх, как в скрипте."));
        cycle(12, Material.LEATHER_BOOTS, "Когда рубит", "activation", ACTIVATIONS, ACTIVATION_NAMES,
                List.of("Можно рубить по бревну, присев,", "или наоборот только присев."));
        number(13, Material.ANVIL, "Износ топора",
                List.of("Множитель износа за каждое бревно.", "×1 — как в ванилле, ×0 — не тратить.", "«Прочность» на топоре учитывается."),
                (int) Math.round(plugin.getConfig().getDouble(key("durability-multiplier"), 1.0) * 10), 1, 5, 0, 50,
                v -> "×" + String.format(Locale.ROOT, "%.1f", v / 10.0), v -> plugin.set(key("durability-multiplier"), v / 10.0));
        toggle(14, Material.SHIELD, "Беречь топор",
                List.of("Если топор сломается на этом дереве —", "срубится только одно бревно."),
                bool("protect-axe", true), v -> plugin.set(key("protect-axe"), v));
        toggle(15, Material.GRASS_BLOCK, "В креативе", List.of("Рубить целиком и в креативе."),
                bool("in-creative", false), v -> plugin.set(key("in-creative"), v));
        number(16, Material.CLOCK, "Скорость падения",
                List.of("Сколько тиков валится дерево", "(20 тиков = 1 секунда)."),
                num("fall-ticks", 24), 2, 10, 6, 80, v -> v + " т", v -> plugin.set(key("fall-ticks"), v));

        // --- лимиты
        number(19, Material.OAK_LOG, "Макс. брёвен", List.of("Больше — не рубит целиком", "(защита от лагов)."),
                num("max-logs", 150), 10, 50, 10, 1000, Integer::toString, v -> plugin.set(key("max-logs"), v));
        number(20, Material.COMPASS, "Радиус дерева", List.of("Насколько далеко от пня", "могут быть его брёвна."),
                num("max-radius", 10), 1, 5, 2, 32, v -> v + " бл.", v -> plugin.set(key("max-radius"), v));
        number(21, Material.OAK_LEAVES, "Макс. листвы", List.of("Сколько листьев срубить", "с одним деревом."),
                num("max-leaves", 400), 50, 200, 0, 2000, Integer::toString, v -> plugin.set(key("max-leaves"), v));
        number(23, Material.REPEATER, "Задержка «по одному»", List.of("Тиков между брёвнами", "в режиме «По одному бревну»."),
                num("sequential-delay-ticks", 2), 1, 5, 1, 20, v -> v + " т", v -> plugin.set(key("sequential-delay-ticks"), v));

        // --- листва и дроп
        toggle(28, Material.AZALEA_LEAVES, "Только настоящие деревья",
                List.of("Нужна природная листва рядом —", "дома из брёвен не снесёт."),
                bool("require-leaves", true), v -> plugin.set(key("require-leaves"), v));
        number(29, Material.MOSS_BLOCK, "Мин. листьев", List.of("Сколько природных листьев", "нужно, чтобы считать деревом."),
                num("min-leaves", 4), 1, 5, 0, 50, Integer::toString, v -> plugin.set(key("min-leaves"), v));
        toggle(30, Material.SHEARS, "Рубить листву",
                List.of("Листва этого дерева осыпается листочками", "и даёт дроп. Соседние деревья не трогает."),
                bool("break-leaves", true), v -> plugin.set(key("break-leaves"), v));
        toggle(31, Material.CHEST, "Дроп у пня",
                List.of("Весь дроп у пня, а не там,", "куда упало дерево."),
                bool("drops-at-stump", false), v -> plugin.set(key("drops-at-stump"), v));
        toggle(32, Material.OAK_SAPLING, "Сажать саженец",
                List.of("На месте пня сажается саженец", "(у толстых деревьев — 4)."),
                bool("replant", true), v -> plugin.set(key("replant"), v));
        toggle(33, Material.BONE_MEAL, "Саженец не бесплатный",
                List.of("Берётся из дропа дерева", "или из инвентаря игрока."),
                bool("replant-needs-sapling", true), v -> plugin.set(key("replant-needs-sapling"), v));

        back(() -> new MainMenu(plugin, viewer));
    }

    /** Переключатель по кругу: ЛКМ — следующее значение, ПКМ — предыдущее. */
    private void cycle(int slot, Material material, String name, String path, String[] values, String[] names, List<String> description) {
        String current = plugin.getConfig().getString(key(path), values[0]);
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(current)) {
                index = i;
            }
        }
        List<String> lore = new ArrayList<>(description);
        lore.add("");
        for (int i = 0; i < values.length; i++) {
            lore.add((i == index ? "<green>▶ " : "<dark_gray>  ") + names[i]);
        }
        lore.add("");
        lore.add("<yellow>ЛКМ/ПКМ</yellow> — переключить");
        int at = index;
        button(slot, Icon.of(material).name("<aqua>" + name + ": <white>" + names[index]).lore(lore).build(), type -> {
            int next = (at + (type.isRightClick() ? values.length - 1 : 1)) % values.length;
            plugin.set(key(path), values[next]);
            redraw();
        });
    }
}
