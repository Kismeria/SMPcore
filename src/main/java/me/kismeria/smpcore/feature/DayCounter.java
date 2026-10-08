package me.kismeria.smpcore.feature;

import me.kismeria.smpcore.SmpCore;
import me.kismeria.smpcore.StateStore.Phase;
import me.kismeria.smpcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Счётчик дней как в Vanilla Refresh: в момент наступления нового дня над хотбаром
 * собирается «— ДЕНЬ N —» с тихими щелчками (дни с 1). Каждый сотый день — юбилейная версия:
 * мигающие золотые черты, перезвон аметиста. На вход в игру не показывается.
 */
public final class DayCounter {

    private final SmpCore plugin;
    private long lastDay = -1;

    public DayCounter(SmpCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    private void tick() {
        long day = plugin.mainWorld().getFullTime() / 24000L;
        // первый замер или время откатили назад (/time set) — просто запоминаем
        if (lastDay < 0 || day < lastDay) {
            lastDay = day;
            return;
        }
        if (day == lastDay) {
            return;
        }
        lastDay = day;
        if (plugin.flag("features.day-counter") && plugin.start().phase() != Phase.LOBBY) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                play(player, day + 1);
            }
        }
    }

    private void play(Player player, long day) {
        String word = plugin.lang().raw(player, "day.word");
        String number = String.valueOf(day);
        boolean milestone = day % 100 == 0;
        String dash = plugin.lang().raw(player, "day.color");
        String gold = plugin.lang().raw(player, "day.milestone-color");

        // кадры: черта, две черты, раздвинулись, буквы по одной, число
        List<String> frames = new ArrayList<>();
        frames.add("—");
        frames.add("——");
        frames.add("— —");
        for (int i = 1; i <= word.length(); i++) {
            frames.add("— " + word.substring(0, i) + " —");
        }
        int[] at = new int[frames.size()];
        int tick = 0;
        for (int i = 0; i < at.length; i++) {
            at[i] = tick;
            tick += i == 1 ? 25 : 5;
        }
        int reveal = tick + 15;
        int end = reveal + (milestone ? 100 : 60);

        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                for (int i = 0; i < at.length; i++) {
                    if (t == at[i]) {
                        plugin.hud().message(player, Text.mm(dash + Text.esc(frames.get(i))), 60, "daycounter", true);
                        click(player, 2f);
                    }
                }
                if (t == reveal) {
                    click(player, 2f);
                    click(player, 1.5f);
                    player.playSound(player, Sound.BLOCK_COPPER_BREAK, SoundCategory.MASTER, 0.6f, 0.6f);
                    if (milestone) {
                        player.playSound(player, Sound.BLOCK_VAULT_PLACE, SoundCategory.MASTER, 0.4f, 2f);
                        player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.MASTER, 1f, 0.5f);
                        player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.MASTER, 1f, 0.7f);
                        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 40, 0.6, 0.8, 0.6, 0.3);
                    }
                }
                if (t >= reveal && (t - reveal) % 5 == 0 && t < end) {
                    // юбилей — черты переливаются золотом, обычный день — просто держим надпись
                    String color = milestone && ((t - reveal) / 5) % 2 == 0 ? gold : dash;
                    plugin.hud().message(player, Text.mm(color + "——<reset> <white>" + Text.esc(word) + " " + number + "<reset> " + color + "——"), 60, "daycounter", true);
                    if (milestone && t - reveal == 40) {
                        player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.MASTER, 1f, 1.5f);
                        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.MASTER, 0.5f, 0.63f);
                    }
                }
                if (t++ >= end) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static void click(Player player, float pitch) {
        player.playSound(player, Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.3f, pitch);
    }
}
