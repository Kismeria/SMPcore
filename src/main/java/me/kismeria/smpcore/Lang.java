package me.kismeria.smpcore;

import me.kismeria.smpcore.util.Text;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import net.kyori.adventure.translation.Translator;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Сообщения на языке клиента игрока (как в FlectonePulse).
 * Файлы: plugins/SmpCore/lang/&lt;locale&gt;.yml. Можно добавить свой, например uk_ua.yml —
 * подхватится по языку клиента. Не найден язык — берётся default-language из config.yml.
 */
public final class Lang {

    private static final List<String> BUNDLED = List.of("ru_ru", "en_us");

    /**
     * Ключи, которые клиент получает как translate-компонент (ачивки и т.п.).
     * Paper переводит их на язык каждого игрока прямо при отправке пакета.
     */
    private static final List<String> CLIENT_KEYS = List.of("advancement.babel-title", "advancement.babel-description", "soul.label",
            "mention.toast", "mention.description");
    public static final String CLIENT_PREFIX = "smpcore.";

    private final SmpCore plugin;
    private final Map<String, YamlConfiguration> files = new HashMap<>();
    private String fallback = "ru_ru";
    private TranslationStore.StringBased<MessageFormat> store;

    public Lang(SmpCore plugin) {
        this.plugin = plugin;
    }

    public void load() {
        files.clear();
        File dir = new File(plugin.getDataFolder(), "lang");
        for (String name : BUNDLED) {
            if (!new File(dir, name + ".yml").exists()) {
                plugin.saveResource("lang/" + name + ".yml", false);
            }
        }
        File[] list = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (list != null) {
            for (File file : list) {
                String name = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                // новые ключи из обновлений плагина подтягиваются из jar
                InputStream bundled = plugin.getResource("lang/" + name + ".yml");
                if (bundled != null) {
                    yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
                }
                files.put(name, yaml);
            }
        }
        fallback = plugin.getConfig().getString("default-language", "ru_ru").toLowerCase(Locale.ROOT);
        if (!files.containsKey(fallback)) {
            fallback = "ru_ru";
        }
        registerClientTranslations();
    }

    private void registerClientTranslations() {
        if (store != null) {
            GlobalTranslator.translator().removeSource(store);
        }
        store = TranslationStore.messageFormat(Key.key("smpcore", "lang"));
        Locale defaultLocale = Translator.parseLocale(fallback);
        if (defaultLocale != null) {
            store.defaultLocale(defaultLocale);
        }
        for (String name : files.keySet()) {
            Locale locale = Translator.parseLocale(name);
            if (locale == null) {
                continue;
            }
            for (String key : CLIENT_KEYS) {
                // в MessageFormat апостроф — спецсимвол
                store.register(CLIENT_PREFIX + key, locale, new MessageFormat(raw(name, key).replace("'", "''"), locale));
            }
        }
        GlobalTranslator.translator().addSource(store);
    }

    public void unregister() {
        if (store != null) {
            GlobalTranslator.translator().removeSource(store);
            store = null;
        }
    }

    public String fallback() {
        return fallback;
    }

    /** Загруженные языки, по алфавиту. */
    public List<String> available() {
        return files.keySet().stream().sorted().toList();
    }

    public String localeOf(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.locale().toString().toLowerCase(Locale.ROOT);
        }
        return fallback;
    }

    private YamlConfiguration file(String locale) {
        YamlConfiguration exact = files.get(locale);
        if (exact != null) {
            return exact;
        }
        String language = locale.contains("_") ? locale.substring(0, locale.indexOf('_')) : locale;
        for (Map.Entry<String, YamlConfiguration> entry : files.entrySet()) {
            if (entry.getKey().startsWith(language + "_")) {
                return entry.getValue();
            }
        }
        return files.get(fallback);
    }

    public String raw(String locale, String key) {
        YamlConfiguration yaml = file(locale);
        String value = yaml != null ? yaml.getString(key) : null;
        if (value == null) {
            YamlConfiguration def = files.get(fallback);
            value = def != null ? def.getString(key) : null;
        }
        return value != null ? value : key;
    }

    public String raw(CommandSender sender, String key) {
        return raw(localeOf(sender), key);
    }

    public List<String> rawList(String locale, String key) {
        YamlConfiguration yaml = file(locale);
        return yaml != null ? yaml.getStringList(key) : List.of();
    }

    public Component get(CommandSender sender, String key, TagResolver... resolvers) {
        return Text.mm(raw(sender, key), resolvers);
    }

    /** Сообщение с префиксом. */
    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(Text.mm(raw(sender, "prefix") + raw(sender, key), resolvers));
    }

    public void actionBar(Player player, String key, TagResolver... resolvers) {
        plugin.hud().message(player, get(player, key, resolvers));
    }

    public void broadcast(String key, TagResolver... resolvers) {
        broadcast(key, viewer -> TagResolver.resolver(resolvers));
    }

    /** Рассылка, где плейсхолдеры зависят от языка получателя (например, длительности). */
    public void broadcast(String key, Function<CommandSender, TagResolver> resolver) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            send(player, key, resolver.apply(player));
        }
        send(Bukkit.getConsoleSender(), key, resolver.apply(Bukkit.getConsoleSender()));
    }

    /** 1ч 5м 9с на языке получателя. */
    public String duration(CommandSender sender, long totalSeconds) {
        return duration(localeOf(sender), totalSeconds);
    }

    public String duration(String locale, long totalSeconds) {
        long s = Math.max(0, totalSeconds);
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append(raw(locale, "time.d")).append(' ');
        if (h > 0) sb.append(h).append(raw(locale, "time.h")).append(' ');
        if (m > 0) sb.append(m).append(raw(locale, "time.m")).append(' ');
        if ((sec > 0 && s < 3600) || sb.isEmpty()) sb.append(sec).append(raw(locale, "time.s"));
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- плейсхолдеры

    /** Плейсхолдер с MiniMessage внутри. */
    public static TagResolver ph(String name, Object value) {
        return Placeholder.parsed(name, String.valueOf(value));
    }

    /** Плейсхолдер с текстом как есть (ники, ввод игрока). */
    public static TagResolver txt(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver comp(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
