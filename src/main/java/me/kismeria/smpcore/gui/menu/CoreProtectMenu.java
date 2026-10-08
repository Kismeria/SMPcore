package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Icon;
import me.kismeria.smpcore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Пресеты CoreProtect для игрока: проверить, что он делал, и откатить. Результаты — в чат. */
public final class CoreProtectMenu extends Menu {

    private final Player target;

    public CoreProtectMenu(SmpCore plugin, Player viewer, Player target) {
        super(plugin, viewer);
        this.target = target;
    }

    @Override
    protected String title() {
        return "<dark_gray>CoreProtect · " + Text.esc(target.getName());
    }

    @Override
    protected void draw() {
        String user = "u:" + target.getName() + " r:#global";

        // --- посмотреть (ничего не меняет)
        lookup(10, Material.GRASS_BLOCK, "Блоки за 1 час", "Что ставил и ломал.", user + " t:1h a:block");
        lookup(11, Material.STONE, "Блоки за 24 часа", "Что ставил и ломал.", user + " t:24h a:block");
        lookup(12, Material.CHEST, "Сундуки за 24 часа", "Что клал и брал из сундуков,\nбочек, шалкеров, печек.", user + " t:24h a:container");
        lookup(13, Material.HOPPER, "Предметы за 24 часа", "Что выбрасывал и подбирал.", user + " t:24h a:item");
        lookup(14, Material.IRON_SWORD, "Убийства за 7 дней", "Кого убил: мобы и игроки.", user + " t:7d a:kill");
        lookup(15, Material.PAPER, "Чат за 24 часа", "Что писал в чат.", user + " t:24h a:chat");
        lookup(16, Material.COMMAND_BLOCK, "Команды за 24 часа", "Какие команды вводил.", user + " t:24h a:command");
        lookup(20, Material.OAK_DOOR, "Входы и выходы за 7 дней", "Когда заходил и выходил.", user + " t:7d a:session");
        lookup(21, Material.OAK_SIGN, "Таблички за 7 дней", "Что писал на табличках.", user + " t:7d a:sign");
        lookup(23, Material.COMPASS, "Кто что делал рядом со мной", "Все игроки в радиусе 10 блоков\nвокруг тебя за 3 дня.", "r:10 t:3d");
        button(24, Icon.of(Material.SPYGLASS).name("<aqua><bold>Режим инспектора")
                .lore("Включить/выключить.", "ЛКМ по блоку — кто его ломал,",
                        "ПКМ — кто ставил или что брал из сундука.").build(), t -> {
            viewer.closeInventory();
            viewer.performCommand("co inspect");
        });

        // --- откатить
        lookup(28, Material.ENDER_EYE, "Предпросмотр отката 24 ч",
                "Показывает только тебе, что вернётся.\nМир не меняется. Выйди из предпросмотра:\n/co cancel или /co rollback без #preview.",
                user + " t:24h #preview", "rollback");
        rollback(29, Material.CLOCK, "Откатить всё за 1 час", user + " t:1h");
        rollback(30, Material.CLOCK, "Откатить всё за 24 часа", user + " t:24h");
        rollback(31, Material.CLOCK, "Откатить всё за 3 дня", user + " t:3d");
        rollback(32, Material.BARREL, "Откатить только сундуки за 24 ч", user + " t:24h a:container");
        button(33, Icon.of(Material.LIME_DYE).name("<green><bold>Вернуть откаченное за 24 ч")
                .lore("Противоположность отката: возвращает", "изменения игрока обратно (restore).", "",
                        "<yellow>Клик</yellow> — с подтверждением").build(),
                t -> confirm("Вернуть изменения " + target.getName() + " за 24 ч?",
                        () -> run("co restore " + user + " t:24h")));
        button(34, Icon.of(Material.RED_DYE).name("<red><bold>Отменить последний откат")
                .lore("/co undo — отменяет твой последний", "rollback или restore.").build(),
                t -> confirm("Отменить твой последний откат?", () -> run("co undo")));

        back(() -> new PlayerMenu(plugin, viewer, target));
    }

    private void lookup(int slot, Material material, String name, String description, String params) {
        lookup(slot, material, name, description, params, "lookup");
    }

    private void lookup(int slot, Material material, String name, String description, String params, String action) {
        List<String> lore = new ArrayList<>(List.of(description.split("\n")));
        lore.add("");
        lore.add("<dark_gray>/co " + action + " " + Text.esc(params));
        lore.add("<yellow>Клик</yellow> — результат в чат");
        button(slot, Icon.of(material).name("<gold><bold>" + name).lore(lore).build(),
                t -> run("co " + action + " " + params));
    }

    private void rollback(int slot, Material material, String name, String params) {
        button(slot, Icon.of(material).name("<red><bold>" + name)
                .lore("Ломает поставленное, ставит сломанное,", "возвращает вещи в сундуки.", "",
                        "<dark_gray>/co rollback " + Text.esc(params), "<yellow>Клик</yellow> — с подтверждением").build(),
                t -> confirm(name + " (" + target.getName() + ")?", () -> run("co rollback " + params)));
    }

    private void run(String command) {
        viewer.closeInventory();
        viewer.performCommand(command);
    }

    @Override
    protected Menu reopen() {
        return new CoreProtectMenu(plugin, viewer, target);
    }
}
