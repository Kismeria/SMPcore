package me.kismeria.smpcore.feature;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import me.kismeria.smpcore.Lang;
import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** MOTD в списке серверов: случайные строки, фейковый/отрицательный онлайн, фейковые ники, текст вместо версии. */
public final class MotdManager implements Listener {

    private final SmpCore plugin;
    private final List<CachedServerIcon> icons = new ArrayList<>();

    public MotdManager(SmpCore plugin) {
        this.plugin = plugin;
    }

    public void loadIcons() {
        icons.clear();
        File dir = new File(plugin.getDataFolder(), "icons");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                icons.add(Bukkit.loadServerIcon(file));
            } catch (Exception e) {
                plugin.getLogger().warning("Иконка " + file.getName() + " не подошла (нужен PNG 64x64): " + e.getMessage());
            }
        }
    }

    private String option(String key, String def) {
        String value = plugin.getConfig().getString("motd." + key, def);
        return value == null ? def : value.toLowerCase(Locale.ROOT);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent event) {
        if (!plugin.flag("motd.enabled")) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int real = event.getNumPlayers();
        int onlineValue = plugin.getConfig().getInt("motd.online-value", 0);
        int online = switch (option("online-mode", "real")) {
            case "fixed" -> onlineValue;
            case "offset" -> real + onlineValue;
            case "random" -> random.nextInt(Math.max(1, onlineValue) + 1);
            case "negative" -> -Math.max(1, real + Math.abs(onlineValue));
            default -> real;
        };
        int max = switch (option("max-mode", "real")) {
            case "fixed" -> plugin.getConfig().getInt("motd.max-value", 100);
            case "online-plus-one" -> online + 1;
            default -> event.getMaxPlayers();
        };

        List<String> lines = plugin.getConfig().getStringList("motd.lines");
        if (!lines.isEmpty()) {
            String line = lines.get(random.nextInt(lines.size()));
            event.motd(Text.mm(line,
                    Lang.ph("online", online),
                    Lang.ph("max", max),
                    Lang.ph("status", plugin.start().statusText(plugin.lang().fallback()))));
        }
        event.setNumPlayers(online);
        event.setMaxPlayers(max);

        String hover = option("hover-mode", "real");
        if (!hover.equals("real")) {
            event.getListedPlayers().clear();
            if (hover.equals("fake") || hover.equals("text")) {
                for (String entry : plugin.getConfig().getStringList("motd.hover-lines")) {
                    String name = Text.legacy(entry);
                    event.getListedPlayers().add(new PaperServerListPingEvent.ListedPlayerInfo(name,
                            UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))));
                }
            }
        }
        if (plugin.flag("motd.hide-count")) {
            event.setHidePlayers(true);
        }
        String version = plugin.getConfig().getString("motd.version-text", "");
        if (version != null && !version.isBlank()) {
            event.setVersion(Text.legacy(version));
            event.setProtocolVersion(-1);
        }
        if (plugin.flag("motd.random-icon") && !icons.isEmpty()) {
            event.setServerIcon(icons.get(random.nextInt(icons.size())));
        }
    }
}
