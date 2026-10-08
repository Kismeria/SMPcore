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

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MOTD в списке серверов: случайные строки, фейковый/отрицательный онлайн, фейковые ники, текст вместо версии.
 * Иконки сервера — отдельно от MOTD: обычные из plugins/SmpCore/icons/, на техработах — из icons/lockdown/.
 * Подходит любой PNG/JPG/GIF/BMP: картинка сама ужимается до 64x64.
 */
public final class MotdManager implements Listener {

    private static final String LOCKDOWN_DIR = "lockdown";
    private static final String LOCKDOWN_DEFAULT = "lockdown-icon.png";

    private final SmpCore plugin;
    /** Имя файла без регистра → иконка, в порядке имён (для cycle). */
    private final Map<String, CachedServerIcon> icons = new LinkedHashMap<>();
    private final Map<String, CachedServerIcon> lockdownIcons = new LinkedHashMap<>();

    public MotdManager(SmpCore plugin) {
        this.plugin = plugin;
    }

    public void loadIcons() {
        File dir = new File(plugin.getDataFolder(), "icons");
        File lockdownDir = new File(dir, LOCKDOWN_DIR);
        if (!lockdownDir.exists()) {
            if (!lockdownDir.mkdirs()) {
                plugin.getLogger().warning("Не удалось создать папку " + lockdownDir.getPath());
            } else {
                seedLockdownIcon(lockdownDir);
            }
        }
        load(dir, icons);
        load(lockdownDir, lockdownIcons);
    }

    /** Первая иконка техработ: прежняя plugins/SmpCore/lockdown-icon.png, если её меняли, иначе стандартная из плагина. */
    private void seedLockdownIcon(File lockdownDir) {
        File target = new File(lockdownDir, LOCKDOWN_DEFAULT);
        File legacy = new File(plugin.getDataFolder(), LOCKDOWN_DEFAULT);
        try {
            if (legacy.isFile()) {
                Files.copy(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            try (InputStream in = plugin.getResource(LOCKDOWN_DEFAULT)) {
                if (in != null) {
                    Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось положить иконку техработ: " + e.getMessage());
        }
    }

    private void load(File dir, Map<String, CachedServerIcon> into) {
        into.clear();
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".gif") || lower.endsWith(".bmp");
        });
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        for (File file : files) {
            try {
                BufferedImage image = ImageIO.read(file);
                if (image == null) {
                    throw new IOException("не картинка");
                }
                into.put(file.getName().toLowerCase(Locale.ROOT), Bukkit.loadServerIcon(fit(image)));
            } catch (Exception e) {
                plugin.getLogger().warning("Иконка " + file.getName() + " не подошла: " + e.getMessage());
            }
        }
    }

    /** Любой размер → 64x64: обрезаем по центру до квадрата и сглаженно ужимаем. */
    private static BufferedImage fit(BufferedImage source) {
        if (source.getWidth() == 64 && source.getHeight() == 64) {
            return source;
        }
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, 64, 64, x, y, x + side, y + side, null);
        g.dispose();
        return out;
    }

    /**
     * Иконка по режиму из config (icons.* или icons.lockdown.*): random — случайная на каждый пинг,
     * cycle — по очереди раз в cycle-seconds, fixed — файл file. Null — оставить server-icon.png сервера.
     */
    private CachedServerIcon pick(String path, Map<String, CachedServerIcon> pool) {
        if (pool.isEmpty()) {
            return null;
        }
        List<CachedServerIcon> list = List.copyOf(pool.values());
        String mode = plugin.getConfig().getString(path + "mode", "random");
        return switch (mode == null ? "random" : mode.toLowerCase(Locale.ROOT)) {
            case "fixed" -> {
                String file = plugin.getConfig().getString(path + "file", "");
                CachedServerIcon icon = file == null || file.isBlank() ? null : pool.get(file.toLowerCase(Locale.ROOT));
                yield icon != null ? icon : list.getFirst();
            }
            case "cycle" -> {
                long seconds = Math.max(1, plugin.getConfig().getLong(path + "cycle-seconds", 10));
                yield list.get((int) (System.currentTimeMillis() / 1000 / seconds % list.size()));
            }
            default -> list.get(ThreadLocalRandom.current().nextInt(list.size()));
        };
    }

    /** Иконка не зависит от motd.enabled; на техработах — свой набор, без него — обычный. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPingIcon(PaperServerListPingEvent event) {
        if (!plugin.getConfig().getBoolean("icons.enabled", true)) {
            return;
        }
        CachedServerIcon icon = null;
        if (plugin.flag("lockdown.enabled")) {
            icon = pick("icons.lockdown.", lockdownIcons);
        }
        if (icon == null) {
            icon = pick("icons.", icons);
        }
        if (icon != null) {
            event.setServerIcon(icon);
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
    }
}
