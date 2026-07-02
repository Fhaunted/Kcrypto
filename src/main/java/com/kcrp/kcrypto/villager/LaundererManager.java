package com.kcrp.kcrypto.villager;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.config.ConfigManager;
import com.kcrp.kcrypto.database.DatabaseManager;
import com.kcrp.kcrypto.economy.EconomyManager;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LaundererManager – spawns and manages the custom "Le Blanchisseur" villager.
 *
 * <h3>Villager identification</h3>
 * Each spawned launderer is tagged with a {@link PersistentDataType#STRING}
 * PDC entry (key {@code "kkopia_launderer"}) set to the owner's UUID string.
 * This makes identification instant even after server restarts.
 *
 * <h3>Merchant offers</h3>
 * Offers are rebuilt every 24 h when {@link #rebuildMerchantOffers(double)}
 * is called by the rate task. Each offer lets the player trade physical emeralds
 * (acting as KCoin tokens) for a "K-Crypto Voucher" note item that the
 * {@link com.kcrp.kcrypto.listeners.LaundererListener} intercepts to debit the
 * player's crypto balance and credit KCoins via Vault.
 *
 * <p><b>Note:</b> the actual trade interception is done in
 * {@link com.kcrp.kcrypto.listeners.LaundererListener} via
 * {@code TradeSelectEvent} / {@code InventoryClickEvent}. The merchant offers
 * shown here are purely cosmetic/informational. The real debit/credit logic is
 * in the listener.</p>
 */
public final class LaundererManager {

    /** PDC key used to mark launderer entities. */
    public static final String PDC_KEY = "kkopia_launderer";

    /** PDC key that stores the owner UUID on the villager. */
    public static final String PDC_OWNER_KEY = "kkopia_launderer_owner";

    public static final String PDC_TAX_RATE = "tax_rate";
    public static final String PDC_TAX_POOL = "tax_pool";

    public static final String GUI_TITLE = "§8« §6Le Blanchisseur §8»";

    private final KcryptoPlugin   plugin;
    private final DatabaseManager db;
    private final EconomyManager  economy;
    private final ConfigManager   cfg;

    public LaundererManager(KcryptoPlugin plugin, DatabaseManager db,
                            EconomyManager economy, ConfigManager cfg) {
        this.plugin  = plugin;
        this.db      = db;
        this.economy = economy;
        this.cfg     = cfg;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Villager spawning
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Spawns a new Launderer at the specified location and registers it.
     * Must be called from the region thread owning that chunk.
     *
     * @param owner  the player UUID who will receive taxes from this launderer
     * @param loc    spawn location
     * @return the spawned Villager entity
     */
    public Villager spawnLaunderer(UUID owner, Location loc) {
        Villager villager = (Villager) loc.getWorld().spawnEntity(loc, EntityType.VILLAGER);

        villager.setVillagerType(Villager.Type.PLAINS);
        villager.setProfession(Villager.Profession.NITWIT);
        villager.setVillagerLevel(5);
        villager.setCustomName("§8« §6Le Blanchisseur §8»");
        villager.setCustomNameVisible(true);
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.setCanPickupItems(false);

        // Tag with PDC
        NamespacedKey keyLaunderer = new NamespacedKey(plugin, PDC_KEY);
        NamespacedKey keyOwner     = new NamespacedKey(plugin, PDC_OWNER_KEY);
        NamespacedKey keyTaxRate   = new NamespacedKey(plugin, PDC_TAX_RATE);
        NamespacedKey keyTaxPool   = new NamespacedKey(plugin, PDC_TAX_POOL);

        villager.getPersistentDataContainer().set(keyLaunderer, PersistentDataType.BYTE, (byte) 1);
        villager.getPersistentDataContainer().set(keyOwner, PersistentDataType.STRING, owner.toString());
        villager.getPersistentDataContainer().set(keyTaxRate, PersistentDataType.DOUBLE, cfg.getLaundererTaxFraction() * 100.0);
        villager.getPersistentDataContainer().set(keyTaxPool, PersistentDataType.DOUBLE, 0.0);
        plugin.getLogger().info("[KKopia] Launderer spawned at "
                + loc.getWorld().getName() + " " + loc.getBlockX() + ","
                + loc.getBlockY() + "," + loc.getBlockZ()
                + " | owner=" + owner);
        return villager;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Identification
    // ────────────────────────────────────────────────────────────────────────

    /** Returns true if the given entity is a registered Launderer villager. */
    public boolean isLaunderer(org.bukkit.entity.Entity entity) {
        if (!(entity instanceof Villager villager)) return false;
        NamespacedKey key = new NamespacedKey(plugin, PDC_KEY);
        return villager.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * Returns the owner UUID stored in the villager's PDC, or null if not set.
     */
    public UUID getLaundererOwner(Villager villager) {
        NamespacedKey key = new NamespacedKey(plugin, PDC_OWNER_KEY);
        String raw = villager.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Tax Rate and Pool Accessors
    // ────────────────────────────────────────────────────────────────────────

    public double getTaxRate(Villager villager) {
        NamespacedKey key = new NamespacedKey(plugin, PDC_TAX_RATE);
        Double rate = villager.getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        return rate != null ? rate : 5.0;
    }

    public void setTaxRate(Villager villager, double rate) {
        NamespacedKey key = new NamespacedKey(plugin, PDC_TAX_RATE);
        villager.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, rate);
    }

    public double getTaxPool(Villager villager) {
        NamespacedKey key = new NamespacedKey(plugin, PDC_TAX_POOL);
        Double pool = villager.getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        return pool != null ? pool : 0.0;
    }

    public void addTaxPool(Villager villager, double amount) {
        NamespacedKey key = new NamespacedKey(plugin, PDC_TAX_POOL);
        double current = getTaxPool(villager);
        double newPool = Math.min(1000.0, current + amount);
        villager.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, newPool);
    }
    
    public void setTaxPool(Villager villager, double amount) {
        NamespacedKey key = new NamespacedKey(plugin, PDC_TAX_POOL);
        villager.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, amount);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Custom GUI Builder
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Builds the custom inventory GUI for this specific Launderer.
     * Provides different options if the viewer is the owner.
     */
    public org.bukkit.inventory.Inventory buildGUI(Villager villager, org.bukkit.entity.Player viewer, double balance) {
        org.bukkit.inventory.Inventory inv = plugin.getServer().createInventory(villager, 27, GUI_TITLE);

        double rate = economy.getRate();
        double tax = getTaxRate(villager);
        boolean isOwner = viewer.getUniqueId().equals(getLaundererOwner(villager)) || viewer.hasPermission("Kcrypto.blanchisseur");

        // 1. Sell Crypto (Crypto -> KCoins)
        ItemStack sellItem = new ItemStack(org.bukkit.Material.GOLD_INGOT);
        var sellMeta = sellItem.getItemMeta();
        sellMeta.setDisplayName("§e§lVendre K-Crypto");
        sellMeta.setLore(List.of(
                "§7Taux actuel : §a" + String.format("%.4f", rate) + " KCoins",
                "§7Taxe appliquée : §c" + String.format("%.1f", tax) + "%",
                "",
                "§e▶ Cliquez pour vendre vos K-Crypto"
        ));
        sellItem.setItemMeta(sellMeta);
        inv.setItem(11, sellItem);

        // 2. Buy Crypto (KCoins -> Crypto)
        ItemStack buyItem = new ItemStack(org.bukkit.Material.EMERALD);
        var buyMeta = buyItem.getItemMeta();
        buyMeta.setDisplayName("§a§lAcheter K-Crypto");
        buyMeta.setLore(List.of(
                "§7Taux actuel : §a" + String.format("%.4f", rate) + " KCoins",
                "§7Taxe appliquée : §c" + String.format("%.1f", tax) + "%",
                "",
                "§a▶ Cliquez pour acheter des K-Crypto"
        ));
        buyItem.setItemMeta(buyMeta);
        inv.setItem(15, buyItem);

        // 3. Balance Display
        ItemStack balanceItem = new ItemStack(org.bukkit.Material.NETHER_STAR);
        var balanceMeta = balanceItem.getItemMeta();
        balanceMeta.setDisplayName("§b§lVotre Portefeuille");
        balanceMeta.setLore(List.of(
                "§7Solde actuel : §e" + String.format("%.2f", balance) + " K-Crypto",
                "§7(Valeur arrondie dans l'affichage)"
        ));
        balanceItem.setItemMeta(balanceMeta);
        inv.setItem(13, balanceItem);

        // Options for owner
        if (isOwner) {
            // Manage Tax
            ItemStack taxItem = new ItemStack(org.bukkit.Material.NAME_TAG);
            var taxMeta = taxItem.getItemMeta();
            taxMeta.setDisplayName("§6§lModifier la Taxe");
            taxMeta.setLore(List.of(
                    "§7Taxe actuelle : §e" + String.format("%.1f", tax) + "%",
                    "",
                    "§6▶ Cliquez pour changer le taux"
            ));
            taxItem.setItemMeta(taxMeta);
            inv.setItem(22, taxItem);

            // Claim Tax
            double taxPool = getTaxPool(villager);
            ItemStack claimItem = new ItemStack(org.bukkit.Material.CHEST);
            var claimMeta = claimItem.getItemMeta();
            claimMeta.setDisplayName("§b§lRécolter les Taxes");
            claimMeta.setLore(List.of(
                    "§7Cagnotte : §b" + String.format("%.4f", taxPool) + " K-Crypto",
                    "§7Limite : §c1000 K-Crypto",
                    "",
                    "§b▶ Cliquez pour récolter"
            ));
            claimItem.setItemMeta(claimMeta);
            inv.setItem(26, claimItem);
        }

        // Fill empty slots with glass panes
        ItemStack pane = new ItemStack(org.bukkit.Material.GRAY_STAINED_GLASS_PANE);
        var paneMeta = pane.getItemMeta();
        paneMeta.setDisplayName(" ");
        pane.setItemMeta(paneMeta);
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, pane);
            }
        }

        return inv;
    }
}
