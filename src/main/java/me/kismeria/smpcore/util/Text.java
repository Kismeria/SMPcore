package me.kismeria.smpcore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    /** MiniMessage без курсива (для имён и описаний предметов в меню). */
    public static Component mm(String input, TagResolver... resolvers) {
        return MM.deserialize(input, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Экранирует пользовательский текст перед вставкой в MiniMessage. */
    public static String esc(String raw) {
        return MM.escapeTags(raw);
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** MiniMessage → строка с § (для мест, где Minecraft принимает только legacy-текст). */
    public static String legacy(String miniMessage) {
        return LegacyComponentSerializer.legacySection().serialize(MM.deserialize(miniMessage));
    }
}
