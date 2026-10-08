package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Вещи админа на время ваниша: при входе в ваниш инвентарь, броня, вторая рука, опыт, эффекты,
 * хп и сытость уходят в vanish-stash.yml и очищаются; при выходе — возвращаются как были.
 * Файл переживает рестарт и вылет.
 */
public final class VanishStash {

    private final SmpCore plugin;
    private final File file;
    private final YamlConfiguration data;

    public VanishStash(SmpCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "vanish-stash.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public boolean has(Player player) {
        return data.isConfigurationSection(player.getUniqueId().toString());
    }

    /** Сохранить всё и очистить игрока. */
    public void store(Player player) {
        if (has(player)) {
            // уже лежит (например, ваниш включили повторно после вылета) — не затираем
            clear(player);
            return;
        }
        String path = player.getUniqueId().toString();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && !contents[slot].getType().isAir()) {
                data.set(path + ".items." + slot, contents[slot]);
            }
        }
        data.set(path + ".name", player.getName());
        data.set(path + ".level", player.getLevel());
        data.set(path + ".exp", (double) player.getExp());
        data.set(path + ".health", player.getHealth());
        data.set(path + ".food", player.getFoodLevel());
        data.set(path + ".saturation", (double) player.getSaturation());
        data.set(path + ".effects", new ArrayList<>(player.getActivePotionEffects()));
        save();
        clear(player);
    }

    /** Вернуть всё, что лежало. Вещи, взятые в ванише, пропадают. */
    public void restore(Player player) {
        String path = player.getUniqueId().toString();
        ConfigurationSection section = data.getConfigurationSection(path);
        if (section == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        ConfigurationSection items = section.getConfigurationSection("items");
        if (items != null) {
            for (String slot : items.getKeys(false)) {
                ItemStack item = items.getItemStack(slot);
                if (item != null) {
                    inventory.setItem(Integer.parseInt(slot), item);
                }
            }
        }
        player.setLevel(section.getInt("level"));
        player.setExp((float) Math.max(0, Math.min(0.9999, section.getDouble("exp"))));
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(Math.max(1, Math.min(max != null ? max.getValue() : 20, section.getDouble("health", 20))));
        player.setFoodLevel(section.getInt("food", 20));
        player.setSaturation((float) section.getDouble("saturation", 5));
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        List<?> effects = section.getList("effects", List.of());
        for (Object effect : effects) {
            if (effect instanceof PotionEffect potion) {
                player.addPotionEffect(potion);
            }
        }
        data.set(path, null);
        save();
    }

    private static void clear(Player player) {
        player.getInventory().clear();
        player.setItemOnCursor(null);
        player.setLevel(0);
        player.setExp(0);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить vanish-stash.yml: " + e.getMessage());
        }
    }
}
