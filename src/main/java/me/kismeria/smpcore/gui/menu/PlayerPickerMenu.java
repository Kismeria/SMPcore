package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Icon;
import me.kismeria.smpcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;

/** /invsee без ника: выбор игрока из онлайна. */
public final class PlayerPickerMenu extends Menu {

    private static final int[] SLOTS = new int[28];

    static {
        for (int i = 0; i < SLOTS.length; i++) {
            SLOTS[i] = 10 + (i / 7) * 9 + i % 7;
        }
    }

    private final int page;

    public PlayerPickerMenu(SmpCore plugin, Player viewer, int page) {
        super(plugin, viewer);
        this.page = page;
    }

    @Override
    protected String title() {
        return "<dark_gray>Выбор игрока";
    }

    @Override
    protected void draw() {
        List<Player> players = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p != viewer)
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(p -> (Player) p)
                .toList();
        int pages = Math.max(1, (players.size() + SLOTS.length - 1) / SLOTS.length);
        int current = Math.min(page, pages - 1);
        if (players.isEmpty()) {
            set(22, Icon.of(Material.BARRIER).name("<red>Никого нет онлайн").build());
        }
        for (int i = 0; i < SLOTS.length; i++) {
            int index = current * SLOTS.length + i;
            if (index >= players.size()) {
                break;
            }
            Player target = players.get(index);
            button(SLOTS[i], Icon.of(Material.PLAYER_HEAD).head(target).name("<yellow><bold>" + Text.esc(target.getName()))
                    .lore("<yellow>Клик</yellow> — управление").build(), t -> {
                if (target.isOnline()) {
                    new PlayerMenu(plugin, viewer, target).open();
                } else {
                    redraw();
                }
            });
        }
        if (current > 0) {
            button(45, Icon.of(Material.ARROW).name("<yellow>← Страница " + current).build(),
                    t -> new PlayerPickerMenu(plugin, viewer, current - 1).open());
        }
        if (current < pages - 1) {
            button(53, Icon.of(Material.ARROW).name("<yellow>Страница " + (current + 2) + " →").build(),
                    t -> new PlayerPickerMenu(plugin, viewer, current + 1).open());
        }
    }

    @Override
    protected Menu reopen() {
        return new PlayerPickerMenu(plugin, viewer, page);
    }
}
