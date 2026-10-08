package me.kismeria.smpcore;

import me.kismeria.smpcore.command.PunishCommands;
import me.kismeria.smpcore.command.SmpCommand;
import me.kismeria.smpcore.command.SpawnCommands;
import me.kismeria.smpcore.command.BackCommand;
import me.kismeria.smpcore.feature.AntiExploit;
import me.kismeria.smpcore.feature.AuthManager;
import me.kismeria.smpcore.feature.SkinManager;
import me.kismeria.smpcore.feature.PremiumLogin;
import me.kismeria.smpcore.feature.Ambience;
import me.kismeria.smpcore.feature.BabelTower;
import me.kismeria.smpcore.feature.CombatTweaks;
import me.kismeria.smpcore.feature.DayCounter;
import me.kismeria.smpcore.feature.JoinFeatures;
import me.kismeria.smpcore.feature.Mentions;
import me.kismeria.smpcore.feature.Souls;
import me.kismeria.smpcore.feature.WorldTweaks;
import me.kismeria.smpcore.feature.ChatBubbles;
import me.kismeria.smpcore.feature.ChatManager;
import me.kismeria.smpcore.feature.InvisibilityManager;
import me.kismeria.smpcore.feature.InvseeManager;
import me.kismeria.smpcore.feature.OreAlerts;
import me.kismeria.smpcore.feature.VanishManager;
import me.kismeria.smpcore.feature.MotdManager;
import me.kismeria.smpcore.feature.TabManager;
import me.kismeria.smpcore.feature.CooldownManager;
import me.kismeria.smpcore.feature.DimensionControl;
import me.kismeria.smpcore.feature.LobbyGuard;
import me.kismeria.smpcore.feature.MinimapControl;
import me.kismeria.smpcore.feature.MiningLock;
import me.kismeria.smpcore.feature.MobRules;
import me.kismeria.smpcore.feature.MobTweaks;
import me.kismeria.smpcore.feature.Lockdown;
import me.kismeria.smpcore.feature.PluginReload;
import me.kismeria.smpcore.feature.SleepSkip;
import me.kismeria.smpcore.feature.TreeChop;
import me.kismeria.smpcore.feature.AdminFun;
import me.kismeria.smpcore.feature.Poses;
import me.kismeria.smpcore.feature.NameTags;
import me.kismeria.smpcore.feature.NerfListener;
import me.kismeria.smpcore.feature.PunishmentManager;
import me.kismeria.smpcore.feature.VoiceBridge;
import me.kismeria.smpcore.feature.StartManager;
import me.kismeria.smpcore.gui.ChatPrompt;
import me.kismeria.smpcore.gui.MenuListener;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.ZoneId;

public final class SmpCore extends JavaPlugin {

    private StateStore state;
    private Lang lang;
    private StartManager start;
    private CooldownManager cooldowns;
    private DimensionControl dimensions;
    private MiningLock mining;
    private MinimapControl minimap;
    private ChatPrompt prompt;
    private InvisibilityManager invisibility;
    private TabManager tab;
    private MotdManager motd;
    private PunishmentManager punishments;
    private VanishManager vanish;
    private InvseeManager invsee;
    private BabelTower babel;
    private NameTags nameTags;
    private ChatBubbles bubbles;
    private WorldTweaks worldTweaks;
    private Poses poses;
    private SleepSkip sleepSkip;
    private Souls souls;
    private Mentions mentions;
    private AuthManager auth;
    private PremiumLogin premium;
    private SkinManager skins;
    private me.kismeria.smpcore.feature.ActionBarHud hud;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lang = new Lang(this);
        lang.load();
        hud = new me.kismeria.smpcore.feature.ActionBarHud(this);
        state = new StateStore(this);
        state.load();

        start = new StartManager(this);
        cooldowns = new CooldownManager(this);
        dimensions = new DimensionControl(this);
        mining = new MiningLock(this);
        minimap = new MinimapControl(this);
        prompt = new ChatPrompt(this);
        invisibility = new InvisibilityManager(this);
        tab = new TabManager(this);
        motd = new MotdManager(this);
        ChatManager chat = new ChatManager(this);
        punishments = new PunishmentManager(this);
        punishments.load();
        vanish = new VanishManager(this);
        invsee = new InvseeManager(this);
        babel = new BabelTower(this);
        babel.register();
        nameTags = new NameTags(this);
        bubbles = new ChatBubbles(this);
        worldTweaks = new WorldTweaks(this);
        souls = new Souls(this);
        mentions = new Mentions(this);
        auth = new AuthManager(this);
        premium = new PremiumLogin(this);
        new DayCounter(this);
        MobRules mobRules = new MobRules(this);

        skins = new SkinManager(this);
        register(hud, auth, premium, skins, punishments, vanish, invsee, babel, nameTags, bubbles, worldTweaks, souls, mentions,
                new CombatTweaks(this), new Ambience(this), new JoinFeatures(this), mobRules, new MobTweaks(this), new OreAlerts(this), start, cooldowns, dimensions, mining, minimap, prompt, invisibility, tab, motd, chat,
                new LobbyGuard(this), new NerfListener(this), new AntiExploit(this), new MenuListener(this));

        PluginCommand translate = getCommand("smptranslate");
        if (translate != null) {
            translate.setExecutor(chat);
        }

        PunishCommands punishCommands = new PunishCommands(this);
        for (String name : new String[]{"ban", "unban", "mute", "unmute", "kick", "history"}) {
            PluginCommand punish = getCommand(name);
            if (punish != null) {
                punish.setExecutor(punishCommands);
                punish.setTabCompleter(punishCommands);
            }
        }
        bind("vanish", vanish);
        bind("invsee", invsee);
        AdminFun fun = new AdminFun(this);
        bind("bolt", fun);
        bind("svo", fun);
        bind("changepassword", auth);
        bind("shatter", souls);
        SpawnCommands spawnCommands = new SpawnCommands(this);
        bind("spawn", spawnCommands);
        bind("nether", spawnCommands);
        BackCommand back = new BackCommand(this);
        register(back);
        bind("back", back);
        bind("return", back);
        poses = new Poses(this);
        register(poses);
        register(new PluginReload(this));
        sleepSkip = new SleepSkip(this);
        register(sleepSkip);
        Lockdown lockdown = new Lockdown(this);
        register(lockdown);
        bind("lockdown", lockdown);
        TreeChop treeChop = new TreeChop(this);
        register(treeChop);
        bind("treechop", treeChop);
        bind("sit", poses);
        bind("lay", poses);
        bind("crawl", poses);
        bind("resetpassword", auth);
        if (getServer().getPluginManager().getPlugin("voicechat") != null) {
            boolean hooked = VoiceBridge.hook(this);
            getLogger().info(hooked ? "Simple Voice Chat найден — мут глушит и голос." : "Simple Voice Chat есть, но API не отдал сервис.");
        }

        PluginCommand command = getCommand("smp");
        if (command != null) {
            SmpCommand executor = new SmpCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        start.enable();
        minimap.enable();
        nameTags.apply();
        mobRules.enable();
        premium.enable();
        souls.enable();
        tab.restart();
        motd.loadIcons();
        getLogger().info("SmpCore включён. Фаза: " + state.phase());
    }

    @Override
    public void onDisable() {
        if (lang != null) {
            lang.unregister();
        }
        if (start != null) {
            start.disable();
        }
        if (nameTags != null) {
            nameTags.disable();
        }
        if (bubbles != null) {
            bubbles.disable();
        }
        if (souls != null) {
            souls.disable();
        }
        if (poses != null) {
            poses.disable();
        }
        if (sleepSkip != null) {
            sleepSkip.disable();
        }
        if (premium != null) {
            premium.disable();
        }
        if (state != null) {
            state.save();
        }
    }

    private void bind(String name, org.bukkit.command.TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    private void register(Listener... listeners) {
        for (Listener listener : listeners) {
            Bukkit.getPluginManager().registerEvents(listener, this);
        }
    }

    public void reload() {
        reloadConfig();
        lang.load();
        motd.loadIcons();
        tab.restart();
        minimap.broadcast();
        nameTags.apply();
        start.refreshBorder();
        mentions.refreshCompletions();
        dimensions.evacuateAll();
    }

    /** Меняет значение в config.yml и сразу сохраняет. */
    public void set(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
    }

    public boolean flag(String path) {
        return getConfig().getBoolean(path);
    }

    public MotdManager motd() {
        return motd;
    }

    public ZoneId zone() {
        try {
            return ZoneId.of(getConfig().getString("timezone", "Europe/Moscow"));
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    public World mainWorld() {
        String name = getConfig().getString("main-world", "");
        World world = name == null || name.isBlank() ? null : Bukkit.getWorld(name);
        return world != null ? world : Bukkit.getWorlds().getFirst();
    }

    public World worldOf(World.Environment environment) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) {
                return world;
            }
        }
        return null;
    }

    public Lang lang() {
        return lang;
    }

    /** Shared action bar: messages above the hearts, race meters above the hunger bar. */
    public me.kismeria.smpcore.feature.ActionBarHud hud() {
        return hud;
    }

    public WorldTweaks worldTweaks() {
        return worldTweaks;
    }

    public BabelTower babel() {
        return babel;
    }

    public VanishManager vanish() {
        return vanish;
    }

    public InvseeManager invsee() {
        return invsee;
    }

    public AuthManager auth() {
        return auth;
    }

    public SkinManager skins() {
        return skins;
    }

    public PremiumLogin premium() {
        return premium;
    }

    public PunishmentManager punishments() {
        return punishments;
    }

    public InvisibilityManager invisibility() {
        return invisibility;
    }

    public StateStore state() {
        return state;
    }

    public StartManager start() {
        return start;
    }

    public CooldownManager cooldowns() {
        return cooldowns;
    }

    public DimensionControl dimensions() {
        return dimensions;
    }

    public MiningLock mining() {
        return mining;
    }

    public MinimapControl minimap() {
        return minimap;
    }

    public ChatPrompt prompt() {
        return prompt;
    }
}
