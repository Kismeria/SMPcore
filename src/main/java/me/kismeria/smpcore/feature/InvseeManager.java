package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.gui.menu.PlayerMenu;
import me.kismeria.smpcore.gui.menu.PlayerPickerMenu;
import me.kismeria.smpcore.util.Icon;
import me.kismeria.smpcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * /invsee: весь инвентарь игрока в одном окне — основные слоты, хотбар, броня (в слоты брони можно
 * положить что угодно, хоть блок), вторая рука. Изменения сразу уходят игроку, его действия сразу
 * видны админу. Кнопка — эндер-сундук.
 */
public final class InvseeManager implements Listener, TabExecutor {

    /** Слоты окна 0..40 → индексы PlayerInventory. */
    private static final int[] TO_PLAYER = new int[41];
    private static final int EDITABLE = 41;
    private static final int SLOT_INFO = 51;
    private static final int SLOT_ENDER = 52;
    private static final int SLOT_CLOSE = 53;

    static {
        for (int i = 0; i < 27; i++) {
            TO_PLAYER[i] = 9 + i;      // основной инвентарь
        }
        for (int i = 0; i < 9; i++) {
            TO_PLAYER[27 + i] = i;     // хотбар — четвёртым рядом, как в игре
        }
        TO_PLAYER[36] = 39;            // шлем
        TO_PLAYER[37] = 38;            // нагрудник
        TO_PLAYER[38] = 37;            // поножи
        TO_PLAYER[39] = 36;            // ботинки
        TO_PLAYER[40] = 40;            // вторая рука
    }

    private final class View implements InventoryHolder {
        private final Player target;
        private final Inventory inventory;
        private boolean dirty;

        View(Player target) {
            this.target = target;
            this.inventory = Bukkit.createInventory(this, 54, Text.mm("<dark_gray>Инвентарь · " + Text.esc(target.getName())));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SmpCore plugin;
    private final Map<UUID, View> views = new HashMap<>();

    public InvseeManager(SmpCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::pullAll, 4L, 4L);
    }

    public void open(Player admin, Player target) {
        View view = new View(target);
        decorate(view);
        pull(view);
        views.put(admin.getUniqueId(), view);
        admin.openInventory(view.inventory);
    }

    // ---------------------------------------------------------------- синхронизация

    private void pullAll() {
        for (Map.Entry<UUID, View> entry : Map.copyOf(views).entrySet()) {
            View view = entry.getValue();
            Player admin = Bukkit.getPlayer(entry.getKey());
            if (admin == null || admin.getOpenInventory().getTopInventory().getHolder(false) != view) {
                views.remove(entry.getKey());
                continue;
            }
            if (!view.target.isOnline()) {
                admin.closeInventory();
                plugin.lang().send(admin, "invsee.target-left");
                continue;
            }
            if (!view.dirty) {
                pull(view);
            }
        }
    }

    /** Игрок → окно. */
    private void pull(View view) {
        PlayerInventory source = view.target.getInventory();
        for (int slot = 0; slot < EDITABLE; slot++) {
            ItemStack actual = source.getItem(TO_PLAYER[slot]);
            ItemStack shown = view.inventory.getItem(slot);
            if (!same(actual, shown)) {
                view.inventory.setItem(slot, actual == null ? null : actual.clone());
            }
        }
        view.inventory.setItem(SLOT_INFO, info(view.target));
    }

    /** Окно → игрок. */
    private void push(View view) {
        PlayerInventory target = view.target.getInventory();
        for (int slot = 0; slot < EDITABLE; slot++) {
            ItemStack wanted = view.inventory.getItem(slot);
            if (!same(wanted, target.getItem(TO_PLAYER[slot]))) {
                target.setItem(TO_PLAYER[slot], wanted == null ? null : wanted.clone());
            }
        }
        view.dirty = false;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        boolean aEmpty = a == null || a.getType().isAir();
        boolean bEmpty = b == null || b.getType().isAir();
        if (aEmpty || bEmpty) {
            return aEmpty == bEmpty;
        }
        return a.getAmount() == b.getAmount() && a.isSimilar(b);
    }

    private void schedulePush(View view) {
        view.dirty = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (view.target.isOnline()) {
                push(view);
            }
        });
    }

    // ---------------------------------------------------------------- оформление

    private void decorate(View view) {
        ItemStack pane = Icon.of(Material.BLACK_STAINED_GLASS_PANE).name(" ").build();
        for (int slot = 41; slot < 54; slot++) {
            view.inventory.setItem(slot, pane);
        }
        String[] labels = {"Шлем", "Нагрудник", "Поножи", "Ботинки", "Вторая рука"};
        for (int i = 0; i < labels.length; i++) {
            view.inventory.setItem(45 + i, Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                    .name("<gray>↑ " + labels[i]).lore("Можно положить любой предмет,", "даже блок.").build());
        }
        view.inventory.setItem(SLOT_ENDER, Icon.of(Material.ENDER_CHEST).name("<dark_purple><bold>Эндер-сундук")
                .lore("Открыть эндер-сундук игрока.", "Изменения сразу у игрока.").build());
        view.inventory.setItem(SLOT_CLOSE, Icon.of(Material.ARROW).name("<yellow>← Назад").build());
    }

    private static ItemStack info(Player target) {
        AttributeInstance max = target.getAttribute(Attribute.MAX_HEALTH);
        return Icon.of(Material.PLAYER_HEAD).head(target).name("<yellow><bold>" + Text.esc(target.getName()))
                .lore("Хп: <white>" + (int) Math.ceil(target.getHealth()) + "/" + (int) (max != null ? max.getValue() : 20),
                        "Сытость: <white>" + target.getFoodLevel(),
                        "Уровень: <white>" + target.getLevel(),
                        "Режим: <white>" + target.getGameMode().name().toLowerCase(Locale.ROOT),
                        "Мир: <white>" + target.getWorld().getName(),
                        "Позиция: <white>" + target.getLocation().getBlockX() + " " + target.getLocation().getBlockY() + " " + target.getLocation().getBlockZ(),
                        "",
                        "Ряды 1–3 — рюкзак, ряд 4 — хотбар,",
                        "ряд 5 — броня и вторая рука.")
                .build();
    }

    // ---------------------------------------------------------------- клики

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof View view)) {
            return;
        }
        Player admin = (Player) event.getWhoClicked();
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        int raw = event.getRawSlot();
        if (top && raw >= EDITABLE) {
            event.setCancelled(true);
            if (raw == SLOT_ENDER) {
                Bukkit.getScheduler().runTask(plugin, () -> admin.openInventory(view.target.getEnderChest()));
            } else if (raw == SLOT_CLOSE) {
                Bukkit.getScheduler().runTask(plugin, () -> new PlayerMenu(plugin, admin, view.target).open());
            }
            return;
        }
        // двойной клик может собрать стопку и из рамки — запрещаем, если рамка затронута
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        if (top || event.isShiftClick()) {
            schedulePush(view);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof View view)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean touchesTop = false;
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                if (raw >= EDITABLE) {
                    event.setCancelled(true);
                    return;
                }
                touchesTop = true;
            }
        }
        if (touchesTop) {
            schedulePush(view);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof View view) {
            if (view.dirty && view.target.isOnline()) {
                push(view);
            }
            views.remove(event.getPlayer().getUniqueId());
        }
    }

    // ---------------------------------------------------------------- команда

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player admin)) {
            plugin.lang().send(sender, "invsee.only-players");
            return true;
        }
        if (args.length == 0) {
            new PlayerPickerMenu(plugin, admin, 0).open();
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.lang().send(sender, "punish.not-found", Lang.txt("player", args[0]));
            return true;
        }
        if (Objects.equals(target, admin)) {
            plugin.lang().send(sender, "invsee.self");
            return true;
        }
        if (args.length > 1 && (args[1].equalsIgnoreCase("ender") || args[1].equalsIgnoreCase("эндер"))) {
            admin.openInventory(target.getEnderChest());
        } else {
            new PlayerMenu(plugin, admin, target).open();
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 2) {
            return List.of("ender");
        }
        return List.of();
    }
}
