package me.kismeria.smpcore.util;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/** Билдер иконок для меню. Все строки — MiniMessage. */
public final class Icon {

    private final ItemStack item;
    private Component name;
    private final List<Component> lore = new ArrayList<>();
    private boolean glow;
    private OfflinePlayer head;

    private Icon(Material material) {
        this.item = new ItemStack(material);
    }

    public static Icon of(Material material) {
        return new Icon(material);
    }

    public Icon name(String mm) {
        this.name = Text.mm(mm);
        return this;
    }

    public Icon lore(String... lines) {
        for (String line : lines) {
            lore.add(Text.mm(line.isEmpty() ? " " : "<gray>" + line));
        }
        return this;
    }

    public Icon lore(List<String> lines) {
        return lore(lines.toArray(String[]::new));
    }

    public Icon glow(boolean glow) {
        this.glow = glow;
        return this;
    }

    public Icon amount(int amount) {
        item.setAmount(Math.max(1, Math.min(99, amount)));
        return this;
    }

    public Icon head(OfflinePlayer owner) {
        this.head = owner;
        return this;
    }

    public ItemStack build() {
        ItemMeta meta = item.getItemMeta();
        if (name != null) {
            meta.displayName(name);
        }
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (head != null && meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(head);
        }
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(
                        DataComponentTypes.ATTRIBUTE_MODIFIERS,
                        DataComponentTypes.ENCHANTMENTS,
                        DataComponentTypes.STORED_ENCHANTMENTS,
                        DataComponentTypes.UNBREAKABLE,
                        DataComponentTypes.FIREWORKS)
                .build());
        return item;
    }
}
