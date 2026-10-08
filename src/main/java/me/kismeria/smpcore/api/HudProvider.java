package me.kismeria.smpcore.api;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Persistent part of a player's action bar, from another plugin (SmpOrigins: race meters).
 * Register: {@code JavaPlugin.getPlugin(SmpCore.class).hud().register(plugin, provider)}.
 * Called every 2 ticks for every online player; return null when there is nothing to show.
 */
public interface HudProvider {

    /** Meter drawn above the hunger bar (about 81 px wide fits exactly). */
    Component bar(Player player);

    /** Text above the hearts, shown when no chat-style action bar message is active. */
    default Component hint(Player player) {
        return null;
    }
}
