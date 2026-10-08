package me.kismeria.smpcore.gui.menu;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.api.SmpAddon;
import me.kismeria.smpcore.feature.CooldownType;
import me.kismeria.smpcore.gui.Menu;
import me.kismeria.smpcore.util.Icon;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Arrays;
import java.util.List;

public final class MainMenu extends Menu {

    public MainMenu(SmpCore plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return "<dark_gray>SMP · Панель управления";
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    public boolean live() {
        return true;
    }

    @Override
    protected void draw() {
        button(11, Icon.of(Material.CLOCK).name("<gold><bold>Старт SMP")
                .lore("Таймер, обратный отсчёт, старт сейчас,", "граница мира, лобби до старта.").build(),
                t -> new StartMenu(plugin, viewer).open());
        button(12, Icon.of(Material.GRASS_BLOCK).name("<green><bold>Мир")
                .lore("Ад, энд, PvP, keepInventory,", "сложность и правила игры.").build(), t -> new WorldMenu(plugin, viewer).open());
        long activeCooldowns = Arrays.stream(CooldownType.values()).filter(plugin.cooldowns()::enabled).count();
        button(13, Icon.of(Material.GOLDEN_APPLE).name("<gold><bold>PvP кулдауны")
                .lore("Яблоки, булава, выпад копья,", "жемчуг, тотем и т.д.", "", "Активно: <white>" + activeCooldowns + "/" + CooldownType.values().length).build(),
                t -> new CooldownMenu(plugin, viewer).open());
        button(14, Icon.of(Material.DIAMOND).name("<aqua><bold>Нёрфы")
                .lore("Fortune на алмазах,", "жители и библиотекари.").build(), t -> new NerfMenu(plugin, viewer).open());
        button(15, Icon.of(Material.DEEPSLATE_DIAMOND_ORE).name("<blue><bold>Добыча")
                .lore("Запрет глубинных руд", "после старта.", "", "Сейчас: " + plugin.mining().status(viewer)).build(),
                t -> new MiningMenu(plugin, viewer).open());

        button(20, Icon.of(Material.SHIELD).name("<red><bold>Защита от абуза")
                .lore("Жемчуг и транспорт за границу,", "слезание сквозь стены, клипы,", "крыша ада.").build(),
                t -> new ProtectionMenu(plugin, viewer).open());
        button(22, Icon.of(Material.NOTE_BLOCK).name("<light_purple><bold>Звуки")
                .lore("Вход и выход игроков,", "упоминание через @ник.").build(),
                t -> new SoundMenu(plugin, viewer).open());
        button(21, Icon.of(Material.IRON_AXE).name("<gold><bold>Рубка деревьев")
                .lore("Дерево падает целиком.", "Анимация, прочность топора,", "листва, саженцы.").build(),
                t -> new TreeChopMenu(plugin, viewer).open());
        toggle(24, Material.FILLED_MAP, "Запрет кейвмода и радара",
                List.of("Миникарты Xaero, VoxelMap, JourneyMap:", "кейвмод и радар сущностей выключены", "у всех, включая админов."),
                plugin.flag("minimap.restrict"), v -> {
                    plugin.set("minimap.restrict", v);
                    plugin.minimap().broadcast();
                });

        // кнопки других плагинов (SmpOrigins и т.п.)
        int[] addonSlots = {23, 29, 30, 31, 32, 33};
        int i = 0;
        for (RegisteredServiceProvider<SmpAddon> reg : Bukkit.getServicesManager().getRegistrations(SmpAddon.class)) {
            if (i >= addonSlots.length) {
                break;
            }
            SmpAddon addon = reg.getProvider();
            button(addonSlots[i++], addon.icon(viewer), t -> addon.open(viewer));
        }
    }
}
