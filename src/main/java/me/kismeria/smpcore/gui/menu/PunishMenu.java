package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.feature.PunishmentManager.Type;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Icon;
import me.kismeria.smpcore.util.Text;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** Выбор срока мута/бана, потом причина в чат. */
public final class PunishMenu extends Menu {

    private static final long[] SECONDS = {15 * 60, 3600, 6 * 3600, 86400, 7 * 86400, 30 * 86400, 0};
    private static final String[] LABELS = {"15 минут", "1 час", "6 часов", "1 день", "7 дней", "30 дней", "Навсегда"};
    private static final Material[] ICONS = {Material.LIME_DYE, Material.YELLOW_DYE, Material.ORANGE_DYE,
            Material.RED_DYE, Material.MAGENTA_DYE, Material.PURPLE_DYE, Material.BLACK_DYE};

    private final OfflinePlayer target;
    private final Type type;

    public PunishMenu(SmpCore plugin, Player viewer, OfflinePlayer target, Type type) {
        super(plugin, viewer);
        this.target = target;
        this.type = type;
    }

    @Override
    protected String title() {
        return "<dark_gray>" + (type == Type.BAN ? "Бан" : "Мут") + " · " + Text.esc(String.valueOf(target.getName()));
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < SECONDS.length; i++) {
            long seconds = SECONDS[i];
            button(10 + i, Icon.of(ICONS[i]).name((type == Type.BAN ? "<red>" : "<gold>") + LABELS[i])
                    .lore("Потом напишешь причину в чат", "(<white>-</white> — без причины).").build(), t ->
                    plugin.prompt().ask(viewer, "prompt.reason", input -> {
                        plugin.punishments().punish(target, type, seconds, input.equals("-") ? "" : input, viewer);
                        Player online = target.getPlayer();
                        if (online != null && type == Type.MUTE) {
                            new PlayerMenu(plugin, viewer, online).open();
                        }
                    }));
        }
        Player online = target.getPlayer();
        if (online != null) {
            back(() -> new PlayerMenu(plugin, viewer, online));
        }
    }
}
