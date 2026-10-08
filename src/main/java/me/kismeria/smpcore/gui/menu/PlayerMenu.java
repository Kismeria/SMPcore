package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.feature.PunishmentManager.Punishment;
import me.kismeria.smpcore.feature.PunishmentManager.Type;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Durations;
import me.kismeria.smpcore.util.Icon;
import me.kismeria.smpcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Управление игроком (/invsee ник): инвентарь, эндер-сундук, лечение, мут, бан, история. */
public final class PlayerMenu extends Menu {

    private final Player target;

    public PlayerMenu(SmpCore plugin, Player viewer, Player target) {
        super(plugin, viewer);
        this.target = target;
    }

    @Override
    protected String title() {
        return "<dark_gray>Игрок · " + Text.esc(target.getName());
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected void draw() {
        if (!target.isOnline()) {
            set(13, Icon.of(Material.BARRIER).name("<red>Игрок вышел").build());
            return;
        }
        String name = target.getName();

        button(10, Icon.of(Material.CHEST).name("<gold><bold>Инвентарь")
                .lore("Весь инвентарь: рюкзак, хотбар,", "броня, вторая рука.", "Можно брать, класть, менять", "броню на любые предметы.").build(),
                t -> plugin.invsee().open(viewer, target));
        button(11, Icon.of(Material.ENDER_CHEST).name("<dark_purple><bold>Эндер-сундук")
                .lore("Изменения сразу у игрока.").build(),
                t -> viewer.openInventory(target.getEnderChest()));
        button(12, Icon.of(Material.GOLDEN_APPLE).name("<red><bold>Вылечить и накормить").build(), t -> {
            AttributeInstance max = target.getAttribute(Attribute.MAX_HEALTH);
            target.setHealth(max != null ? max.getValue() : 20);
            target.setFoodLevel(20);
            target.setSaturation(20);
            target.setFireTicks(0);
            plugin.lang().send(viewer, "admin.healed", Lang.txt("player", name));
        });

        boolean canMute = viewer.hasPermission("smpcore.punish.mute");
        boolean canBan = viewer.hasPermission("smpcore.punish.ban");
        Punishment mute = plugin.punishments().get(target.getUniqueId(), Type.MUTE);
        if (mute != null) {
            button(14, Icon.of(Material.GREEN_WOOL).name("<green><bold>Снять мут").lore(describe(mute)).build(), t -> {
                if (canMute) {
                    plugin.punishments().pardon(target, Type.MUTE, viewer);
                    redraw();
                }
            });
        } else {
            button(14, Icon.of(Material.ORANGE_WOOL).name("<gold><bold>Мут")
                    .lore("Чат, личка и голосовой", "чат (Simple Voice Chat).", canMute ? "" : "<red>Нет права smpcore.punish.mute").build(),
                    t -> {
                        if (canMute) {
                            new PunishMenu(plugin, viewer, target, Type.MUTE).open();
                        }
                    });
        }
        button(15, Icon.of(Material.RED_WOOL).name("<red><bold>Бан")
                .lore("Выбрать срок и причину.", canBan ? "" : "<red>Нет права smpcore.punish.ban").build(), t -> {
            if (canBan) {
                new PunishMenu(plugin, viewer, target, Type.BAN).open();
            }
        });

        List<String> history = plugin.punishments().history(target.getUniqueId());
        List<String> lore = new ArrayList<>();
        if (history.isEmpty()) {
            lore.add("Наказаний не было.");
        }
        for (String entry : history.subList(Math.max(0, history.size() - 8), history.size())) {
            lore.add("<white>" + Text.esc(entry));
        }
        lore.add("");
        lore.add("<yellow>Клик</yellow> — вся история в чат");
        button(16, Icon.of(Material.BOOK).name("<white><bold>История наказаний").lore(lore).build(), t -> {
            viewer.closeInventory();
            viewer.performCommand("history " + name);
        });

        button(13, Icon.of(Material.ARMOR_STAND).name("<aqua><bold>Обновить скин")
                .lore("Заново скачать скин (Mojang, Ely.by,", "TLauncher, LittleSkin, Bedrock — GeyserMC)", "в обход кэша и сразу показать всем,", "без перезахода.").build(), t -> {
            plugin.skins().refresh(List.of(target), viewer);
            viewer.closeInventory();
        });

        // интеграции: InventoryRollbackPlus (слева снизу) и CoreProtect (справа снизу)
        boolean rollbacks = Bukkit.getPluginManager().isPluginEnabled("InventoryRollbackPlus");
        button(18, Icon.of(Material.CHEST).name("<aqua><bold>Откат инвентаря")
                .lore("Бэкапы инвентаря: смерти, входы,", "выходы, смена мира.", "Вернуть вещи, опыт, эндер-сундук.", "",
                        rollbacks ? "<yellow>Клик</yellow> — открыть InventoryRollback" : "<red>InventoryRollbackPlus не установлен").build(),
                t -> {
                    if (rollbacks) {
                        viewer.closeInventory();
                        viewer.performCommand("irp restore " + name);
                    }
                });
        boolean coreProtect = Bukkit.getPluginManager().isPluginEnabled("CoreProtect");
        button(26, Icon.of(Material.SPYGLASS).name("<gold><bold>Действия игрока")
                .lore("CoreProtect: что ставил, ломал, брал", "из сундуков, писал. Откаты блоков.", "",
                        coreProtect ? "<yellow>Клик</yellow> — пресеты" : "<red>CoreProtect не установлен").build(),
                t -> {
                    if (coreProtect) {
                        new CoreProtectMenu(plugin, viewer, target).open();
                    }
                });
    }

    private static List<String> describe(Punishment p) {
        long left = p.secondsLeft(System.currentTimeMillis());
        return List.of("Осталось: <white>" + (left < 0 ? "навсегда" : Durations.human(left)),
                "Выдал: <white>" + Text.esc(p.by()),
                "Причина: <white>" + (p.reason().isBlank() ? "—" : Text.esc(p.reason())), "",
                "<yellow>Клик</yellow> — снять");
    }

    @Override
    protected Menu reopen() {
        return new PlayerMenu(plugin, viewer, target);
    }
}
