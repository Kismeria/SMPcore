package me.kismeria.smpcore.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Кнопка другого плагина в главном меню /smp.
 * Регистрация: getServer().getServicesManager().register(SmpAddon.class, addon, plugin, ServicePriority.Normal).
 */
public interface SmpAddon {

    /** Иконка кнопки (строится заново при каждой отрисовке меню). */
    ItemStack icon(Player viewer);

    /** Клик по кнопке. */
    void open(Player viewer);
}
