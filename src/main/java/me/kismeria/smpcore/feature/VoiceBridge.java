package me.kismeria.smpcore.feature;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import me.kismeria.smpcore.SmpCore;

import java.util.UUID;

/**
 * Мут в Simple Voice Chat: голос замученного игрока не доходит ни до кого.
 * Класс грузится, только если на сервере стоит плагин voicechat.
 */
public final class VoiceBridge implements VoicechatPlugin {

    private final SmpCore plugin;

    private VoiceBridge(SmpCore plugin) {
        this.plugin = plugin;
    }

    /** @return true, если подключились к Simple Voice Chat */
    public static boolean hook(SmpCore plugin) {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            return false;
        }
        service.registerPlugin(new VoiceBridge(plugin));
        return true;
    }

    @Override
    public String getPluginId() {
        return "smpcore";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection connection = event.getSenderConnection();
        // после /plugins reload старый мост остаётся в Simple Voice Chat — он молчит, работает новый
        if (connection == null || !plugin.isEnabled()) {
            return;
        }
        UUID id = connection.getPlayer().getUuid();
        if (plugin.flag("punish.voice-mute") && plugin.punishments().isMuted(id)) {
            event.cancel();
            plugin.punishments().notifyVoiceMuted(id);
            return;
        }
        plugin.babel().heard(id);
    }
}
