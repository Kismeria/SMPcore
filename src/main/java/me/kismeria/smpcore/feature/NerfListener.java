package me.kismeria.smpcore.feature;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import me.kismeria.smpcore.SmpCore;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Перенос kdiamondnerf и LibrarianNerf:
 * Fortune на алмазах даёт 1 алмаз, жители не продают алмазный шмот,
 * библиотекарь продаёт книги только с N уровня, но с максимальными чарами.
 */
public final class NerfListener implements Listener {

    private final SmpCore plugin;
    private final NamespacedKey bookMarker;

    public NerfListener(SmpCore plugin) {
        this.plugin = plugin;
        // тот же ключ, что был у LibrarianNerf, — старые книги жителей останутся «нашими»
        this.bookMarker = new NamespacedKey("librariannerf", "max-enchant-book");
    }

    // ---------------------------------------------------------------- алмазы

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDiamondBreak(BlockBreakEvent event) {
        if (!plugin.flag("nerfs.diamond-fortune")) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType() != Material.DIAMOND_ORE && block.getType() != Material.DEEPSLATE_DIAMOND_ORE) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.getType().isAir()
                || tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0
                || tool.getEnchantmentLevel(Enchantment.FORTUNE) <= 0
                || block.getDrops(tool, player).isEmpty()) {
            return;
        }
        event.setDropItems(false);
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().dropItemNaturally(center, new ItemStack(Material.DIAMOND, 1));
        event.setExpToDrop(3 + ThreadLocalRandom.current().nextInt(5));
    }

    private static boolean isDiamondGear(ItemStack item) {
        if (item == null) {
            return false;
        }
        return switch (item.getType()) {
            case DIAMOND_PICKAXE, DIAMOND_AXE, DIAMOND_SHOVEL, DIAMOND_HOE, DIAMOND_SWORD, DIAMOND_SPEAR,
                 DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- жители

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAcquireTrade(VillagerAcquireTradeEvent event) {
        if (plugin.flag("nerfs.villager-diamond-gear") && isDiamondGear(event.getRecipe().getResult())) {
            event.setCancelled(true);
        }
        if (event.getEntity() instanceof Villager villager) {
            Bukkit.getScheduler().runTask(plugin, () -> enforceLibrarian(villager));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Villager villager) {
            enforceLibrarian(villager);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTradeOpen(InventoryOpenEvent event) {
        if (!plugin.flag("nerfs.villager-diamond-gear")
                || !(event.getInventory() instanceof MerchantInventory inventory)
                || !(inventory.getMerchant() instanceof Villager)) {
            return;
        }
        Merchant merchant = inventory.getMerchant();
        List<MerchantRecipe> kept = new ArrayList<>();
        boolean changed = false;
        for (MerchantRecipe recipe : merchant.getRecipes()) {
            if (isDiamondGear(recipe.getResult())) {
                changed = true;
            } else {
                kept.add(recipe);
            }
        }
        if (changed) {
            merchant.setRecipes(kept);
        }
    }

    // ---------------------------------------------------------------- библиотекарь

    private String lib(String key) {
        return "nerfs.librarian." + key;
    }

    private void enforceLibrarian(Villager villager) {
        if (!plugin.flag(lib("enabled")) || villager.getProfession() != Villager.Profession.LIBRARIAN) {
            return;
        }
        List<MerchantRecipe> recipes = villager.getRecipes();
        List<MerchantRecipe> result = new ArrayList<>();
        boolean hasOurs = false;
        boolean changed = false;
        for (MerchantRecipe recipe : recipes) {
            ItemStack out = recipe.getResult();
            if (out.getType() != Material.ENCHANTED_BOOK) {
                result.add(recipe);
            } else if (isOurBook(out)) {
                hasOurs = true;
                result.add(recipe);
            } else {
                // ванильная книга — убираем
                changed = true;
            }
        }
        int tradeLevel = plugin.getConfig().getInt(lib("trade-level"), 3);
        if (villager.getVillagerLevel() >= tradeLevel) {
            if (!hasOurs) {
                result.addAll(buildBooks());
                changed = true;
            }
        } else if (hasOurs) {
            result.removeIf(recipe -> recipe.getResult().getType() == Material.ENCHANTED_BOOK && isOurBook(recipe.getResult()));
            changed = true;
        }
        if (changed) {
            villager.setRecipes(result);
        }
    }

    private boolean isOurBook(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(bookMarker, PersistentDataType.BYTE);
    }

    private List<MerchantRecipe> buildBooks() {
        List<String> only = lower(plugin.getConfig().getStringList(lib("only")));
        List<String> exclude = lower(plugin.getConfig().getStringList(lib("exclude")));
        List<Enchantment> pool = new ArrayList<>();
        for (Enchantment enchantment : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) {
            String id = enchantment.getKey().toString().toLowerCase(Locale.ROOT);
            boolean allowed = only.isEmpty() ? !exclude.contains(id) : only.contains(id);
            if (allowed) {
                pool.add(enchantment);
            }
        }
        Collections.shuffle(pool);
        int count = Math.min(Math.max(1, plugin.getConfig().getInt(lib("books-count"), 6)), pool.size());
        List<MerchantRecipe> books = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            books.add(bookRecipe(pool.get(i)));
        }
        return books;
    }

    private MerchantRecipe bookRecipe(Enchantment enchantment) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            storage.addStoredEnchant(enchantment, enchantment.getMaxLevel(), true);
        }
        meta.getPersistentDataContainer().set(bookMarker, PersistentDataType.BYTE, (byte) 1);
        book.setItemMeta(meta);

        int min = clamp(plugin.getConfig().getInt(lib("min-price"), 32));
        int max = Math.max(min, clamp(plugin.getConfig().getInt(lib("max-price"), 64)));
        int price = ThreadLocalRandom.current().nextInt(min, max + 1);

        MerchantRecipe recipe = new MerchantRecipe(book, Math.max(1, plugin.getConfig().getInt(lib("max-uses"), 3)));
        recipe.addIngredient(new ItemStack(Material.EMERALD, price));
        if (plugin.getConfig().getBoolean(lib("require-book"), true)) {
            recipe.addIngredient(new ItemStack(Material.BOOK, 1));
        }
        recipe.setExperienceReward(true);
        recipe.setVillagerExperience(2);
        return recipe;
    }

    private static int clamp(int price) {
        return Math.max(1, Math.min(64, price));
    }

    private static List<String> lower(List<String> list) {
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
