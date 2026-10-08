package me.kismeria.smpcore.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Вызывается после «Сбросить SMP» (меню или /smp reset). */
public final class SmpResetEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
