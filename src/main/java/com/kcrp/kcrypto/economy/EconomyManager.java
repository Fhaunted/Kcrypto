package com.kcrp.kcrypto.economy;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.config.ConfigManager;
import com.kcrp.kcrypto.database.DatabaseManager;
import org.bukkit.entity.Player;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * EconomyManager – maintains the live K-Crypto ↔ KCoin rate
 * and exposes helper methods for balance operations.
 *
 * <p>The rate is stored in an {@link AtomicReference} so async and
 * region-thread code can both read it safely without locks.</p>
 */
public final class EconomyManager {

    private final KcryptoPlugin    plugin;
    private final DatabaseManager db;
    private final ConfigManager   cfg;

    /** 1 K-Crypto = {@code rate} KCoins. Thread-safe via AtomicReference. */
    private final AtomicReference<Double> currentRate;

    public EconomyManager(KcryptoPlugin plugin, DatabaseManager db, ConfigManager cfg) {
        this.plugin      = plugin;
        this.db          = db;
        this.cfg         = cfg;
        this.currentRate = new AtomicReference<>(cfg.getBaseRate());
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Rate management
    // ────────────────────────────────────────────────────────────────────────

    /** Updates the in-memory rate. Called by {@link RateTask}. */
    public void setRate(double newRate) {
        currentRate.set(Math.max(cfg.getRateFloor(), newRate));
    }

    /** Returns the current rate: how many KCoins equals 1 K-Crypto. */
    public double getRate() {
        return currentRate.get();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Conversion
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Converts K-Crypto amount to KCoins using the current rate.
     *
     * @param crypto amount in K-Crypto
     * @return equivalent KCoins
     */
    public double cryptoToKCoins(double crypto) {
        return crypto * getRate();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Yield table (ore → K-Crypto per consumption tick)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Returns how much K-Crypto is awarded per single ore item consumed.
     * Ore types are ranked by rarity/value.
     */
    public static double getOreYield(org.bukkit.Material material) {
        return switch (material) {
            case COAL, COAL_ORE, DEEPSLATE_COAL_ORE                          -> 0.05;
            case COPPER_INGOT, COPPER_ORE, DEEPSLATE_COPPER_ORE,
                 RAW_COPPER                                                   -> 0.10;
            case IRON_INGOT, IRON_ORE, DEEPSLATE_IRON_ORE, RAW_IRON          -> 0.20;
            case GOLD_INGOT, GOLD_ORE, DEEPSLATE_GOLD_ORE, RAW_GOLD,
                 NETHER_GOLD_ORE                                              -> 0.50;
            case REDSTONE, REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE              -> 0.30;
            case LAPIS_LAZULI, LAPIS_ORE, DEEPSLATE_LAPIS_ORE               -> 0.35;
            case EMERALD, EMERALD_ORE, DEEPSLATE_EMERALD_ORE                -> 1.00;
            case DIAMOND, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE                -> 2.50;
            case ANCIENT_DEBRIS, NETHERITE_INGOT,
                 NETHERITE_SCRAP                                              -> 5.00;
            default                                                           -> 0.0;  // not an ore
        };
    }

    /** Returns true if the given material is accepted by the K-Miner. */
    public static boolean isAcceptedOre(org.bukkit.Material material) {
        return getOreYield(material) > 0.0;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Heat contribution per ore (higher-value ores run hotter)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Returns how many heat points (0–100 scale) processing one unit of
     * this ore contributes to the machine's heat gauge.
     */
    public static double getOreHeat(org.bukkit.Material material) {
        double yield = getOreYield(material);
        // Scale: yield * 3 heat per ore, capped at 15 per single item
        return Math.min(15.0, yield * 3.0);
    }
}
