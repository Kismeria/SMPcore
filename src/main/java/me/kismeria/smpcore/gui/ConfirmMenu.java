package me.kismeria.smpcore.gui;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class ConfirmMenu extends Menu {

    private final String question;
    private final Runnable action;
    private final Runnable back;

    public ConfirmMenu(SmpCore plugin, Player viewer, String question, Runnable action, Runnable back) {
        super(plugin, viewer);
        this.question = question;
        this.action = action;
        this.back = back;
    }

    @Override
    protected String title() {
        return "<dark_gray>Подтверждение";
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void draw() {
        set(13, Icon.of(Material.PAPER).name("<white>" + question).lore("Это действие сразу применится.").build());
        button(11, Icon.of(Material.LIME_CONCRETE).name("<green><bold>Да").build(), type -> {
            action.run();
            back.run();
        });
        button(15, Icon.of(Material.RED_CONCRETE).name("<red><bold>Нет").build(), type -> back.run());
    }
}
