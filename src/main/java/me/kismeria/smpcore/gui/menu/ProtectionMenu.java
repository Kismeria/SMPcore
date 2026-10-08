package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class ProtectionMenu extends Menu {

    public ProtectionMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Защита от абуза";
    }

    @Override
    protected void draw() {
        set(4, Icon.of(Material.SHIELD).name("<red><bold>Защита от абуза")
                .lore("Действует на всех, кроме", "наблюдателей. Команды и плагины", "телепортируют без ограничений.").build());

        flag(10, Material.ENDER_PEARL, "Телепорт за границу",
                List.of("Отменяет жемчуг, хорус, порталы", "и слезание с сущности за границу."), "border-teleport");
        flag(11, Material.STRUCTURE_VOID, "Возврат из-за границы",
                List.of("Кто оказался за границей —", "возвращается внутрь."), "border-return");
        flag(12, Material.SADDLE, "Слезание сквозь стену",
                List.of("Слезть с лошади/лодки по ту", "сторону стены нельзя."), "dismount-through-walls");
        flag(13, Material.ENDER_EYE, "Жемчуг в блоки",
                List.of("Жемчуг не закидывает внутрь", "блоков (клип сквозь потолок,", "стены, люки)."), "pearl-clip");
        flag(14, Material.OAK_BOAT, "Телепорт на транспорте",
                List.of("Лодка/лошадь/вагонетка не может", "прыгнуть далеко за один шаг", "(чит-телепорт через сущность)."), "vehicle-speed");
        flag(15, Material.BEDROCK, "Крыша ада",
                List.of("Нельзя быть выше Y " + plugin.getConfig().getInt("protection.nether-roof-y", 127), "в аду."), "nether-roof");

        number(23, Material.COMPARATOR, "Лимит транспорта", List.of("Сколько блоков транспорт может", "пройти за одно движение."),
                plugin.getConfig().getInt("protection.vehicle-max-blocks", 10), 1, 5, 4, 64,
                v -> v + " бл.", v -> plugin.set("protection.vehicle-max-blocks", v));

        toggle(28, Material.DIAMOND, "Алерт о добыче",
                List.of("Админам (smpcore.alerts) пишет,", "если игрок копает много", "алмазов/обломков за короткое время.",
                        "Клик по алерту — тп к игроку."), plugin.flag("alerts.enabled"), v -> plugin.set("alerts.enabled", v));
        number(29, Material.DIAMOND_ORE, "Порог: алмазы", List.of("Сколько руды за окно — алерт."),
                plugin.getConfig().getInt("alerts.diamond-threshold", 12), 1, 5, 1, 200, Integer::toString,
                v -> plugin.set("alerts.diamond-threshold", v));
        number(30, Material.ANCIENT_DEBRIS, "Порог: обломки", List.of("Сколько обломков за окно — алерт."),
                plugin.getConfig().getInt("alerts.debris-threshold", 6), 1, 5, 1, 200, Integer::toString,
                v -> plugin.set("alerts.debris-threshold", v));
        number(31, Material.CLOCK, "Окно", List.of("За какое время считать."),
                plugin.getConfig().getInt("alerts.window-minutes", 10), 1, 10, 1, 240, v -> v + " мин",
                v -> plugin.set("alerts.window-minutes", v));

        back(() -> new MainMenu(plugin, viewer));
    }

    private void flag(int slot, Material material, String name, List<String> description, String key) {
        toggle(slot, material, name, description, plugin.flag("protection." + key), v -> plugin.set("protection." + key, v));
    }
}
