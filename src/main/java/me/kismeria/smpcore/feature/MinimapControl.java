package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Отключение кейвмода и радара сущностей в клиентских миникартах. Работает для всех игроков,
 * включая операторов: ни у одного из протоколов нет исключений для админов.
 *
 * <ul>
 *   <li>Xaero's Minimap / World Map — системное сообщение с кодом fair-play
 *       (отключает и кейвмод, и радар сразу, раздельно Xaero не умеет).</li>
 *   <li>VoxelMap — пакет {@code voxelmap:settings}: байт 0 + JSON.</li>
 *   <li>JourneyMap — пакет {@code journeymap:perm_req}: байт 42, флаг админа, JSON GlobalProperties, два флага.</li>
 * </ul>
 */
public final class MinimapControl implements Listener {

    public static final String XAERO_FAIR_PLAY = "§f§a§i§r§x§a§e§r§o";
    public static final String XAERO_RESET = "§r§e§s§e§t§x§a§e§r§o";
    public static final String VOXELMAP = "voxelmap:settings";
    public static final String JOURNEYMAP = "journeymap:perm_req";

    private final SmpCore plugin;

    public MinimapControl(SmpCore plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, VOXELMAP);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, JOURNEYMAP);
    }

    /** Кейвмод и радар выключаются одной настройкой. */
    public boolean caveDisabled() {
        return plugin.flag("minimap.restrict");
    }

    public boolean radarDisabled() {
        return plugin.flag("minimap.restrict");
    }

    /** Разослать текущие настройки всем онлайн. */
    public void broadcast() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendXaero(player, true);
            sendVoxelMap(player);
            sendJourneyMap(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // ждём, пока клиент создаст сессию миникарты
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sendXaero(player, false);
                sendVoxelMap(player);
                sendJourneyMap(player);
            }
        }, 40L);
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        String channel = event.getChannel();
        if (!channel.equals(VOXELMAP) && !channel.equals(JOURNEYMAP)) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                if (channel.equals(VOXELMAP)) {
                    sendVoxelMap(player);
                } else {
                    sendJourneyMap(player);
                }
            }
        }, 5L);
    }

    /** VoxelMap и JourneyMap могут сбросить настройки при смене измерения — шлём снова (в чат ничего не пишется). */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                sendVoxelMap(player);
                sendJourneyMap(player);
            }
        }, 20L);
    }

    // ---------------------------------------------------------------- Xaero

    private void sendXaero(Player player, boolean sendReset) {
        boolean restrict = caveDisabled() || radarDisabled();
        if (!restrict) {
            if (sendReset) {
                player.sendMessage(Component.text(XAERO_RESET));
            }
            return;
        }
        player.sendMessage(Component.text(XAERO_FAIR_PLAY));
    }

    // ---------------------------------------------------------------- VoxelMap

    private void sendVoxelMap(Player player) {
        if (!player.getListeningPluginChannels().contains(VOXELMAP)) {
            return;
        }
        boolean radar = !radarDisabled();
        String json = "{\"radarAllowed\":" + radar
                + ",\"radarMobsAllowed\":" + radar
                + ",\"radarPlayersAllowed\":" + radar
                + ",\"cavesAllowed\":" + !caveDisabled() + "}";
        Buffer buf = new Buffer();
        buf.writeByte(0);
        buf.writeUtf(json);
        player.sendPluginMessage(plugin, VOXELMAP, buf.toByteArray());
    }

    // ---------------------------------------------------------------- JourneyMap

    private void sendJourneyMap(Player player) {
        if (!player.getListeningPluginChannels().contains(JOURNEYMAP)) {
            return;
        }
        String cave = caveDisabled() ? "NONE" : "ALL";
        String radar = radarDisabled() ? "NONE" : "ALL";
        String radarFlag = radarDisabled() ? "false" : "true";
        String json = "{"
                + "\"journeymapEnabled\":\"true\","
                + "\"caveMapping\":\"" + cave + "\","
                + "\"radarEnabled\":\"" + radar + "\","
                + "\"worldPlayerRadar\":\"" + radar + "\","
                + "\"seeUndergroundPlayers\":\"" + radar + "\","
                + "\"playerRadarEnabled\":\"" + radarFlag + "\","
                + "\"villagerRadarEnabled\":\"" + radarFlag + "\","
                + "\"animalRadarEnabled\":\"" + radarFlag + "\","
                + "\"mobRadarEnabled\":\"" + radarFlag + "\""
                + "}";
        Buffer buf = new Buffer();
        buf.writeByte(42);
        buf.writeBoolean(false);
        buf.writeUtf(json);
        buf.writeBoolean(false);
        buf.writeBoolean(false);
        player.sendPluginMessage(plugin, JOURNEYMAP, buf.toByteArray());
    }

    /** Минимальный аналог FriendlyByteBuf. */
    private static final class Buffer extends ByteArrayOutputStream {

        void writeByte(int value) {
            write(value);
        }

        void writeBoolean(boolean value) {
            write(value ? 1 : 0);
        }

        void writeVarInt(int value) {
            while ((value & ~0x7F) != 0) {
                write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            write(value);
        }

        void writeUtf(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeVarInt(bytes.length);
            write(bytes, 0, bytes.length);
        }
    }
}
