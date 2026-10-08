package me.kismeria.smpcore.gui;

import me.kismeria.smpcore.SmpCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuListener implements Listener {

    private final SmpCore plugin;

    public MenuListener(SmpCore plugin) {
        this.plugin = plugin;
        // живые меню (таймеры) обновляются раз в секунду
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu menu && menu.live()) {
                    menu.redraw();
                }
            }
        }, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("smpcore.admin")) {
            event.getWhoClicked().closeInventory();
            return;
        }
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            int slot = event.getRawSlot();
            ClickType type = event.getClick();
            // открывать/закрывать инвентарь внутри InventoryClickEvent небезопасно — тиком позже
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                    menu.click(slot, type);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
