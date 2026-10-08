package me.kismeria.smpcore.util;

import me.kismeria.smpcore.SmpCore;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.entity.Player;

/**
 * Иконка платформы перед ником: дёрн — Java, бедрок — Bedrock.
 * Java-клиенты (1.21.9+) рисуют спрайт блока прямо в тексте. Bedrock так не умеет —
 * там, где текст рисуется для каждого читателя отдельно (чат), бедрокеру отдаём цветной значок.
 */
public final class PlatformIcon {

    private PlatformIcon() {
    }

    /** Иконка + пробел; пусто, если выключено. */
    public static Component of(SmpCore plugin, Player subject, boolean bedrockViewer) {
        if (!plugin.flag("platform-icons.enabled")) {
            return Component.empty();
        }
        boolean bedrock = Bedrock.is(subject.getUniqueId());
        Component icon;
        if (bedrockViewer) {
            icon = Text.mm(plugin.getConfig().getString(bedrock ? "platform-icons.bedrock-fallback" : "platform-icons.java-fallback", ""));
        } else {
            String sprite = plugin.getConfig().getString(bedrock ? "platform-icons.bedrock-sprite" : "platform-icons.java-sprite",
                    bedrock ? "minecraft:block/bedrock" : "minecraft:block/grass_block_side");
            icon = Component.object(ObjectContents.sprite(Key.key(sprite)));
        }
        return icon.append(Component.space());
    }

    public static boolean isBedrock(Object viewer) {
        return viewer instanceof Player player && Bedrock.is(player.getUniqueId());
    }
}
