package me.kismeria.smpcore.gui;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Icon;
import me.kismeria.smpcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** Базовое меню: рамка, кнопки, переключатели, числовые степперы. */
public abstract class Menu implements InventoryHolder {

    @FunctionalInterface
    public interface Click {
        void handle(ClickType type);
    }

    private static final ItemStack FRAME = Icon.of(Material.BLACK_STAINED_GLASS_PANE).name(" ").build();
    private static final ItemStack FILL = Icon.of(Material.GRAY_STAINED_GLASS_PANE).name(" ").build();

    protected final SmpCore plugin;
    protected final Player viewer;
    private final Map<Integer, Click> clicks = new HashMap<>();
    private Inventory inventory;

    protected Menu(SmpCore plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    protected abstract String title();

    protected abstract void draw();

    protected int rows() {
        return 6;
    }

    /** Перерисовывать раз в секунду (для таймеров). */
    public boolean live() {
        return false;
    }

    public final void open() {
        inventory = Bukkit.createInventory(this, rows() * 9, Text.mm(title()));
        redraw();
        viewer.openInventory(inventory);
    }

    public final void redraw() {
        inventory.clear();
        clicks.clear();
        int size = rows() * 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int col = slot % 9;
            boolean edge = row == 0 || row == rows() - 1 || col == 0 || col == 8;
            inventory.setItem(slot, edge ? FRAME : FILL);
        }
        draw();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void click(int slot, ClickType type) {
        Click click = clicks.get(slot);
        if (click == null) {
            return;
        }
        viewer.playSound(viewer, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        click.handle(type);
    }

    // ---------------------------------------------------------------- строительные блоки

    protected final void set(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    protected final void button(int slot, ItemStack item, Click click) {
        inventory.setItem(slot, item);
        clicks.put(slot, click);
    }

    protected final void back(Supplier<Menu> parent) {
        button(rows() * 9 - 5, Icon.of(Material.ARROW).name("<yellow>← Назад").build(), type -> parent.get().open());
    }

    protected final void toggle(int slot, Material material, String name, List<String> description,
                                boolean value, Consumer<Boolean> setter) {
        List<String> lore = new ArrayList<>(description);
        lore.add("");
        lore.add(value ? "<green>● Включено" : "<red>○ Выключено");
        lore.add("");
        lore.add("<yellow>Клик</yellow> — переключить");
        button(slot, Icon.of(material).name((value ? "<green>" : "<red>") + name).lore(lore).glow(value).build(), type -> {
            setter.accept(!value);
            redraw();
        });
    }

    /**
     * Число: ЛКМ +step, ПКМ −step, с Shift — bigStep.
     */
    protected final void number(int slot, Material material, String name, List<String> description,
                                int value, int step, int bigStep, int min, int max,
                                IntFunction<String> format, IntConsumer setter) {
        List<String> lore = new ArrayList<>(description);
        lore.add("");
        lore.add("Значение: <white>" + format.apply(value));
        lore.add("");
        lore.add("<yellow>ЛКМ</yellow> +" + format.apply(step) + "  <yellow>ПКМ</yellow> −" + format.apply(step));
        lore.add("<yellow>Shift</yellow> — шаг " + format.apply(bigStep));
        button(slot, Icon.of(material).name("<aqua>" + name).lore(lore).build(), type -> {
            int delta = switch (type) {
                case LEFT -> step;
                case RIGHT -> -step;
                case SHIFT_LEFT -> bigStep;
                case SHIFT_RIGHT -> -bigStep;
                default -> 0;
            };
            if (delta == 0) {
                return;
            }
            setter.accept(Math.max(min, Math.min(max, value + delta)));
            redraw();
        });
    }

    protected final void confirm(String question, Runnable action) {
        new ConfirmMenu(plugin, viewer, question, action, () -> this.reopen().open()).open();
    }

    /** Новый экземпляр этого же меню (для возврата после подтверждения). */
    protected Menu reopen() {
        return this;
    }
}
