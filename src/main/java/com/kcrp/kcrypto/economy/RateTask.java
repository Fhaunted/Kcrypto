package com.kcrp.kcrypto.economy;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.config.ConfigManager;
import com.kcrp.kcrypto.database.DatabaseManager;
import com.kcrp.kcrypto.villager.LaundererManager;

import java.util.logging.Logger;

/**
 * RateTask – executed every 24 hours on Folia's AsyncScheduler.
 *
 * <h3>Rate Formula</h3>
 * <pre>
 *   totalCirculation = SUM(balance) from kconomy_wallets AND kconomy_accounts
 *
 *   volatilityFactor = log10(1 + totalCirculation / 1_000_000)
 *                    clipped to [0, 4]
 *
 *   rate = baseRate * (1 + volatilityFactor)
 * </pre>
 *
 * <p>This means:
 * <ul>
 *   <li>At low circulation: rate ≈ baseRate (e.g. 100 KCoins)</li>
 *   <li>At 1 M KCoins in circulation: rate ≈ 200</li>
 *   <li>At 100 M KCoins in circulation: rate ≈ 500</li>
 * </ul>
 *
 * <p>The multiplier is logarithmic, so it grows with the economy but
 * never explodes, giving a realistic inflation-hedge behaviour.</p>
 */
public final class RateTask implements Runnable {

    private final KcryptoPlugin      plugin;
    private final DatabaseManager   db;
    private final EconomyManager    economy;
    private final LaundererManager  launderer;
    private final ConfigManager     cfg;

    public RateTask(KcryptoPlugin plugin, DatabaseManager db,
                    EconomyManager economy, LaundererManager launderer,
                    ConfigManager cfg) {
        this.plugin    = plugin;
        this.db        = db;
        this.economy   = economy;
        this.launderer = launderer;
        this.cfg       = cfg;
    }

    @Override
    public void run() {
        Logger log = plugin.getLogger();
        try {
            double totalCirculation = db.getTotalKCoinsInCirculation();

            // Log(10) of the circulation in millions, clamped to [0, 4]
            double volatilityFactor = Math.log10(1.0 + totalCirculation / 1_000_000.0);
            volatilityFactor = Math.max(0.0, Math.min(4.0, volatilityFactor));

            // Apply base rate and volatility multiplier
            double newRate = cfg.getBaseRate() * (1.0 + volatilityFactor);
            newRate = Math.max(cfg.getRateFloor(), newRate);

            economy.setRate(newRate);
            log.info(String.format(
                    "[KKopia Economy] 24h rate update: totalCirculation=%.2f | rate=%.4f KCoins per K-Crypto",
                    totalCirculation, newRate));

        } catch (Exception e) {
            log.severe("[KKopia Economy] Rate update task failed: " + e.getMessage());
        }
    }
}
