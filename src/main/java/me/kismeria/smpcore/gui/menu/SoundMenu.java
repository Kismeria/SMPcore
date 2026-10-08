package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.feature.JoinFeatures;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Звуки входа и выхода игроков. */
public final class SoundMenu extends Menu {

    private static final String[][] SOUNDS = {
            {"minecraft:block.note_block.bell", "Колокольчик"},
            {"minecraft:block.note_block.chime", "Перезвон"},
            {"minecraft:block.note_block.pling", "Плинк"},
            {"minecraft:block.note_block.harp", "Арфа"},
            {"minecraft:block.note_block.bass", "Бас"},
            {"minecraft:block.note_block.flute", "Флейта"},
            {"minecraft:block.amethyst_block.chime", "Аметист"},
            {"minecraft:entity.experience_orb.pickup", "Опыт"},
            {"minecraft:entity.player.levelup", "Новый уровень"},
            {"minecraft:entity.item.pickup", "Подбор предмета"},
            {"minecraft:entity.chicken.egg", "Яйцо"},
            {"minecraft:block.beacon.activate", "Маяк включился"},
            {"minecraft:block.beacon.deactivate", "Маяк выключился"},
            {"minecraft:ui.toast.in", "Тост: появление"},
            {"minecraft:ui.toast.out", "Тост: исчезновение"},
            {"minecraft:block.wooden_door.open", "Дверь открылась"},
            {"minecraft:block.wooden_door.close", "Дверь закрылась"},
            {"minecraft:block.ender_chest.open", "Эндер-сундук"},
            {"minecraft:entity.enderman.teleport", "Телепорт эндермена"},
            {"minecraft:entity.villager.yes", "Житель: да"},
            {"minecraft:entity.villager.no", "Житель: нет"},
            {"minecraft:entity.cat.ambient", "Кот"},
            {"minecraft:entity.goat.screaming.ambient", "Орущий козёл"},
            {"minecraft:block.bell.use", "Колокол"},
    };

    public SoundMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Звуки";
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void draw() {
        column("join", "Вход", Material.OAK_DOOR, 10, List.of("Слышат все, кроме", "самого игрока.", "Ванишеры звука не дают."));
        column("quit", "Выход", Material.IRON_DOOR, 14, List.of("Слышат все, кроме", "самого игрока.", "Ванишеры звука не дают."));
        column("mention", "Упоминание", Material.BELL, 28, List.of("Слышит тот, кого", "упомянули через @ник."));
        back(() -> new MainMenu(plugin, viewer));
    }

    private void column(String type, String label, Material icon, int slot, List<String> description) {
        String path = "sounds." + type;
        toggle(slot, icon, "Звук: " + label, description,
                plugin.flag(path + ".enabled"), v -> plugin.set(path + ".enabled", v));

        String current = plugin.getConfig().getString(path + ".sound", SOUNDS[0][0]);
        int index = indexOf(current);
        List<String> lore = new ArrayList<>();
        lore.add("Сейчас: <white>" + (index >= 0 ? SOUNDS[index][1] : current));
        lore.add("<dark_gray>" + current);
        lore.add("");
        lore.add("<yellow>ЛКМ</yellow> — следующий");
        lore.add("<yellow>ПКМ</yellow> — предыдущий");
        lore.add("<yellow>Shift</yellow> — прослушать");
        button(slot + 1, Icon.of(Material.NOTE_BLOCK).name("<aqua><bold>Звук: " + label).lore(lore).build(), t -> {
            int next = switch (t) {
                case LEFT -> (index + 1) % SOUNDS.length;
                case RIGHT -> (index - 1 + SOUNDS.length) % SOUNDS.length;
                default -> -1;
            };
            if (next >= 0) {
                plugin.set(path + ".sound", SOUNDS[next][0]);
            }
            preview(path);
            redraw();
        });

        int pitch = (int) Math.round(plugin.getConfig().getDouble(path + ".pitch", 1.0) * 10);
        number(slot + 2, Material.AMETHYST_SHARD, "Высота: " + label.toLowerCase(Locale.ROOT), List.of("Ниже — басовитее,", "выше — звонче."),
                pitch, 1, 5, 5, 20, v -> String.format(Locale.ROOT, "%.1f", v / 10.0), v -> {
                    plugin.set(path + ".pitch", v / 10.0);
                    preview(path);
                });
        int volume = (int) Math.round(plugin.getConfig().getDouble(path + ".volume", 0.6) * 10);
        number(slot + 11, Material.BELL, "Громкость: " + label.toLowerCase(Locale.ROOT), List.of("Насколько громко."),
                volume, 1, 5, 1, 10, v -> v * 10 + "%", v -> {
                    plugin.set(path + ".volume", v / 10.0);
                    preview(path);
                });
    }

    private void preview(String path) {
        JoinFeatures.preview(viewer, plugin.getConfig().getString(path + ".sound", SOUNDS[0][0]),
                (float) plugin.getConfig().getDouble(path + ".volume", 0.6),
                (float) plugin.getConfig().getDouble(path + ".pitch", 1.0));
    }

    private static int indexOf(String sound) {
        for (int i = 0; i < SOUNDS.length; i++) {
            if (SOUNDS[i][0].equals(sound)) {
                return i;
            }
        }
        return -1;
    }
}
