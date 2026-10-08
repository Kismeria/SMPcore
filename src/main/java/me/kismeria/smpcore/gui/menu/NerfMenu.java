package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class NerfMenu extends Menu {

    private static final String[] LEVELS = {"", "Новичок", "Ученик", "Подмастерье", "Знаток", "Мастер"};

    public NerfMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Нёрфы";
    }

    @Override
    protected void draw() {
        set(4, Icon.of(Material.DIAMOND).name("<aqua><bold>Нёрфы").lore("Замедляют прогресс в игре.").build());

        toggle(11, Material.DEEPSLATE_DIAMOND_ORE, "Fortune на алмазах",
                List.of("Кирка с удачей даёт", "ровно 1 алмаз с руды.", "Шёлковое касание не трогаем."),
                plugin.flag("nerfs.diamond-fortune"), v -> plugin.set("nerfs.diamond-fortune", v));
        toggle(13, Material.DIAMOND_CHESTPLATE, "Жители без алмазного шмота",
                List.of("Жители не продают алмазные", "инструменты, оружие и броню."),
                plugin.flag("nerfs.villager-diamond-gear"), v -> plugin.set("nerfs.villager-diamond-gear", v));
        toggle(15, Material.BOOKSHELF, "Нёрф библиотекаря",
                List.of("Книги только с уровня профессии,", "зато с максимальными чарами.", "Ванильные книги убираются."),
                plugin.flag(lib("enabled")), v -> plugin.set(lib("enabled"), v));

        set(22, Icon.of(Material.LECTERN).name("<yellow><bold>Библиотекарь").lore("Настройки ниже.", "Списки чар — в config.yml.").build());
        number(28, Material.EXPERIENCE_BOTTLE, "Уровень профессии", List.of("С какого уровня продаёт книги."),
                plugin.getConfig().getInt(lib("trade-level"), 3), 1, 1, 1, 5, v -> v + " (" + LEVELS[v] + ")", v -> plugin.set(lib("trade-level"), v));
        number(29, Material.ENCHANTED_BOOK, "Книг в продаже", List.of("Сколько разных книг", "добавить библиотекарю."),
                plugin.getConfig().getInt(lib("books-count"), 6), 1, 5, 1, 30, Integer::toString, v -> plugin.set(lib("books-count"), v));
        number(30, Material.EMERALD, "Мин. цена", List.of("Изумруды, нижняя граница."),
                plugin.getConfig().getInt(lib("min-price"), 32), 1, 8, 1, 64, v -> v + " изумр.", v -> {
                    plugin.set(lib("min-price"), v);
                    if (plugin.getConfig().getInt(lib("max-price")) < v) {
                        plugin.set(lib("max-price"), v);
                    }
                });
        number(31, Material.EMERALD_BLOCK, "Макс. цена", List.of("Изумруды, верхняя граница."),
                plugin.getConfig().getInt(lib("max-price"), 64), 1, 8, 1, 64, v -> v + " изумр.", v -> {
                    plugin.set(lib("max-price"), v);
                    if (plugin.getConfig().getInt(lib("min-price")) > v) {
                        plugin.set(lib("min-price"), v);
                    }
                });
        number(32, Material.HOPPER, "Покупок до блокировки", List.of("Сколько раз можно купить", "книгу до перезарядки."),
                plugin.getConfig().getInt(lib("max-uses"), 3), 1, 5, 1, 64, Integer::toString, v -> plugin.set(lib("max-uses"), v));
        toggle(33, Material.BOOK, "Нужна обычная книга",
                List.of("Вторым ингредиентом —", "1 книга, как в ванилле."),
                plugin.getConfig().getBoolean(lib("require-book"), true), v -> plugin.set(lib("require-book"), v));

        set(40, Icon.of(Material.PAPER).name("<gray>Важно")
                .lore("Новые настройки получают книги,", "которые библиотекарь выдаст потом.", "Уже выставленные сделки не меняются.").build());

        back(() -> new MainMenu(plugin, viewer));
    }

    private static String lib(String key) {
        return "nerfs.librarian." + key;
    }
}
