package com.kcrp.kcrypto.items;

import com.kcrp.kcrypto.KcryptoPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * MinerItem – builds and identifies the K-Miner placeable block item.
 *
 * <h3>Design</h3>
 * The item is an Observer block. A {@link PersistentDataType#BYTE} tag 
 * {@code "kminer"} marks it so listeners can reliably detect it at any point.
 */
public final class MinerItem {

    /** PersistentDataContainer key used to flag this item as a K-Miner. */
    public static final String PDC_KEY = "kminer";

    private MinerItem() {}

    // ────────────────────────────────────────────────────────────────────────
    //  Item construction
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Creates a single K-Miner item ready to be given to a player.
     *
     * @param plugin plugin instance (needed for NamespacedKey)
     * @return ItemStack configured with display name, lore, and PDC tag
     */
    public static ItemStack build(KcryptoPlugin plugin) {
        ItemStack item = new ItemStack(Material.OBSERVER);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName("\u00a76\u00a7l\u26a1 K-Miner");
        meta.setLore(List.of(
                "\u00a78\u00bb \u00a7eRig de minage K-Crypto",
                "\u00a78\u00bb \u00a77Ins\u00e9rez des minerais pour g\u00e9n\u00e9rer des KCoins virtuels.",
                "\u00a78\u00bb \u00a7cSurchauffe \u00e0 100% \u2014 refroidissement 30 min.",
                "",
                "\u00a78[\u00a7aCraft\u00e9\u00a78] \u00a7fKKopia"
        ));

        // Mark as K-Miner via PDC
        NamespacedKey key = new NamespacedKey(plugin, PDC_KEY);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);

        // Custom model data for resource pack texture
        meta.setCustomModelData(1001);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Returns true if the given ItemStack is a K-Miner item.
     */
    public static boolean isMiner(KcryptoPlugin plugin, ItemStack item) {
        if (item == null || item.getType() != Material.OBSERVER) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        NamespacedKey key = new NamespacedKey(plugin, PDC_KEY);
        return meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Crafting recipe
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Registers the K-Miner's shaped crafting recipe.
     *
     * <pre>
     *  I O I
     *  R D R
     *  I O I
     *
     *  I = Iron Ingot
     *  O = Observer
     *  R = Redstone Block
     *  D = Diamond
     * </pre>
     */
    public static void registerRecipe(KcryptoPlugin plugin) {
        ItemStack result = build(plugin);
        NamespacedKey recipeKey = new NamespacedKey(plugin, "kminer_recipe");

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, result);
        recipe.shape("IOI", "RDR", "IOI");
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('O', Material.OBSERVER);
        recipe.setIngredient('R', Material.REDSTONE_BLOCK);
        recipe.setIngredient('D', Material.DIAMOND);

        // Remove the recipe first in case of a server reload to prevent errors
        plugin.getServer().removeRecipe(recipeKey);
        plugin.getServer().addRecipe(recipe);
        plugin.getLogger().info("K-Miner crafting recipe registered.");
    }
}
