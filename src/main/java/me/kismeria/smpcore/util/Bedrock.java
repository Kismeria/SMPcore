package me.kismeria.smpcore.util;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Игрок с Bedrock через Geyser + Floodgate. Floodgate проверяет его аккаунт Xbox,
 * поэтому пароль и скины с Java-сервисов ему не нужны (скин Floodgate ставит сам).
 */
public final class Bedrock {

    private static final java.util.Map<UUID, Boolean> LINKED = new java.util.concurrent.ConcurrentHashMap<>();
    private static Object api;
    private static Method isFloodgatePlayer;
    private static boolean resolved;

    private Bedrock() {
    }

    public static boolean present() {
        return Bukkit.getPluginManager().getPlugin("floodgate") != null;
    }

    /** Xbox user id (decimal), for the GeyserMC skin API. Null when unknown. */
    public static String xuid(UUID id) {
        if (id == null) {
            return null;
        }
        try {
            Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = type.getMethod("getInstance").invoke(null);
            Object player = type.getMethod("getPlayer", UUID.class).invoke(instance, id);
            if (player != null) {
                Object xuid = player.getClass().getMethod("getXuid").invoke(player);
                if (xuid != null && !xuid.toString().isBlank()) {
                    return xuid.toString();
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // no Floodgate API: fall back to the UUID layout
        }
        // Floodgate UUID: 00000000-0000-0000-<xuid as 64-bit hex>
        return id.getMostSignificantBits() == 0 ? Long.toString(id.getLeastSignificantBits()) : null;
    }

    public static boolean is(UUID id) {
        if (id == null || !present()) {
            return false;
        }
        // UUID Floodgate-игрока: 00000000-0000-0000-xxxx-xxxxxxxxxxxx (xuid в младших битах)
        if (id.getMostSignificantBits() == 0) {
            return true;
        }
        // привязанные аккаунты (/linkaccount) — только через API Floodgate (кэш: зовут на каждую частицу)
        Boolean cached = LINKED.get(id);
        if (cached != null) {
            return cached;
        }
        boolean linked = linked(id);
        if (LINKED.size() > 10_000) {
            LINKED.clear();
        }
        LINKED.put(id, linked);
        return linked;
    }

    private static boolean linked(UUID id) {
        try {
            if (!resolved) {
                resolved = true;
                Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                api = type.getMethod("getInstance").invoke(null);
                isFloodgatePlayer = type.getMethod("isFloodgatePlayer", UUID.class);
            }
            return api != null && (boolean) isFloodgatePlayer.invoke(api, id);
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }
}
