package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class LobbyMenu extends Menu {

    public LobbyMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Лобби";
    }

    @Override
    protected void draw() {
        set(4, Icon.of(Material.BELL).name("<yellow><bold>Лобби до старта")
                .lore("Действует только в фазе лобби.", "Обход: креатив или", "<white>smpcore.bypass.lobby").build());

        flag(20, Material.IRON_SWORD, "Без PvP", "Игроки не бьют друг друга.", "no-pvp");
        flag(21, Material.BRICKS, "Без строительства", "Нельзя ломать и ставить блоки,", "лить и черпать вёдрами.", "no-build");
        flag(22, Material.GOLDEN_CHESTPLATE, "Без урона", "Игроки не получают урон.", "Упал в бездну — в центр.", "no-damage");
        flag(23, Material.COOKED_BEEF, "Без голода", "Сытость не тратится.", "no-hunger");
        flag(24, Material.OBSIDIAN, "Порталы закрыты", "Ни ад, ни энд, ни зажечь", "портал до старта.", "no-portals");
        flag(31, Material.ENDER_PEARL, "Телепорт при входе", "Зашёл в лобби — сразу", "в центр границы.", "teleport-on-join");

        back(() -> new StartMenu(plugin, viewer));
    }

    private void flag(int slot, Material material, String name, String... args) {
        String key = args[args.length - 1];
        List<String> description = List.of(args).subList(0, args.length - 1);
        toggle(slot, material, name, description, plugin.flag("lobby." + key), v -> plugin.set("lobby." + key, v));
    }
}
