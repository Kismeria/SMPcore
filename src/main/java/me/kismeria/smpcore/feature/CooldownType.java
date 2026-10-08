package me.kismeria.smpcore.feature;

import org.bukkit.Material;

public enum CooldownType {
    GOLDEN_APPLE("golden-apple", "Золотое яблоко", Material.GOLDEN_APPLE, Material.GOLDEN_APPLE,
            "После съедения."),
    ENCHANTED_GOLDEN_APPLE("enchanted-golden-apple", "Зачарованное яблоко", Material.ENCHANTED_GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE,
            "После съедения."),
    ENDER_PEARL("ender-pearl", "Эндер-жемчуг", Material.ENDER_PEARL, Material.ENDER_PEARL,
            "После броска. Ваниль — 1с."),
    WIND_CHARGE("wind-charge", "Заряд ветра", Material.WIND_CHARGE, Material.WIND_CHARGE,
            "После броска. Ваниль — 0.5с."),
    CHORUS_FRUIT("chorus-fruit", "Плод хоруса", Material.CHORUS_FRUIT, Material.CHORUS_FRUIT,
            "После съедения. Ваниль — 1с."),
    MACE("mace", "Булава", Material.MACE, Material.MACE,
            "После сокрушающего удара (с падения).", "Пока идёт кд — удар с падения", "не проходит, обычные удары работают."),
    SPEAR_LUNGE("spear-lunge", "Копьё: выпад", Material.IRON_SPEAR, null,
            "После выпада (зачарование Lunge).", "Пока идёт кд — рывка нет, укол работает."),
    TRIDENT_RIPTIDE("trident-riptide", "Трезубец: тягун", Material.TRIDENT, Material.TRIDENT,
            "После рывка тягуном."),
    ELYTRA_BOOST("elytra-boost", "Фейерверк в полёте", Material.FIREWORK_ROCKET, Material.FIREWORK_ROCKET,
            "После буста элитр фейерверком."),
    TOTEM("totem", "Тотем бессмертия", Material.TOTEM_OF_UNDYING, Material.TOTEM_OF_UNDYING,
            "После срабатывания тотема.", "Пока идёт кд — тотем не спасает.");

    private final String key;
    private final String title;
    private final Material icon;
    private final Material visual;
    private final String[] description;

    CooldownType(String key, String title, Material icon, Material visual, String... description) {
        this.key = key;
        this.title = title;
        this.icon = icon;
        this.visual = visual;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    /** Предмет, на который вешается ванильная полоска кд, или null. */
    public Material visual() {
        return visual;
    }

    public String[] description() {
        return description;
    }

    public static CooldownType byConsumable(Material material) {
        return switch (material) {
            case GOLDEN_APPLE -> GOLDEN_APPLE;
            case ENCHANTED_GOLDEN_APPLE -> ENCHANTED_GOLDEN_APPLE;
            case CHORUS_FRUIT -> CHORUS_FRUIT;
            default -> null;
        };
    }

    public static CooldownType byThrowable(Material material) {
        return switch (material) {
            case ENDER_PEARL -> ENDER_PEARL;
            case WIND_CHARGE -> WIND_CHARGE;
            default -> null;
        };
    }
}
