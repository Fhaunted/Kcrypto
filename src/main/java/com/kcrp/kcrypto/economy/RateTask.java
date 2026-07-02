package com.kcrp.kcrypto.economy;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.config.ConfigManager;
import com.kcrp.kcrypto.database.DatabaseManager;
import com.kcrp.kcrypto.machines.MachineManager;
import com.kcrp.kcrypto.villager.LaundererManager;

import java.util.logging.Logger;

/**
 * RateTask – executed every 24 hours on Folia's AsyncScheduler.
 *
 * <h3>Anti-Inflation Rate Formula (Krach Automatique)</h3>
 * <pre>
 *   C    = SUM(crypto_balance) from kkopia_wallets   [total K-Crypto in circulation]
 *
 *   Rate = 100.0 / (1.0 + C × 0.0018)
 * </pre>
 *
 * <p>Key reference points:
 * <ul>
 *   <li>C =    0 → Rate = 100.0 KCoins per K-Crypto (bootstrap; no crypto mined yet)</li>
 *   <li>C = 1000 → Rate ≈  35.7 KCoins per K-Crypto</li>
 *   <li>C = 5000 → Rate =  10.0 KCoins per K-Crypto ("krach automatique" floor)</li>
 * </ul>
 *
 * <h3>Dynamic Mining Difficulty</h3>
 * <pre>
 *   TimeMultiplier = 1.0 + (C / 1000.0)
 * </pre>
 * After updating the rate, this task also pushes the new difficulty multiplier
 * to all active machines via {@link MachineManager#refreshAllDifficulty}.
 *
 * <p>The constant 0.0018 is derived from:
 * <pre>
 *   10 = 100 / (1 + 5000 × k)  ⟹  k = (100/10 − 1) / 5000 = 0.0018
 * </pre>
 */
public final class RateTask implements Runnable {

    private final KcryptoPlugin      plugin;
    private final DatabaseManager   db;
    private final EconomyManager    economy;
    private final LaundererManager  launderer;
    private final MachineManager    machineManager;
    private final ConfigManager     cfg;

    public RateTask(KcryptoPlugin plugin, DatabaseManager db,
                    EconomyManager economy, LaundererManager launderer,
                    MachineManager machineManager, ConfigManager cfg) {
        this.plugin         = plugin;
        this.db             = db;
        this.economy        = economy;
        this.launderer      = launderer;
        this.machineManager = machineManager;
        this.cfg            = cfg;
    }

    @Override
    public void run() {
        Logger log = plugin.getLogger();
        try {
            // ── 1. Query total K-Crypto in circulation (C) ──────────────────
            double totalCirculation = db.getTotalKCryptoInCirculation();

            // ── 2. Anti-inflation rate formula ──────────────────────────────
            //   Rate = 100.0 / (1.0 + C × 0.0018)
            //   C=0    → 100 KCoins  |  C=5000 → 10 KCoins
            double newRate = EconomyManager.computeRate(totalCirculation, cfg.getRateFloor());
            economy.setRate(newRate);

            // ── 3. Dynamic mining difficulty ─────────────────────────────────
            //   TimeMultiplier = 1.0 + (C / 1000.0)
            //   C=0    → ×1  |  C=1000 → ×2  |  C=5000 → ×6
            machineManager.refreshAllDifficulty(totalCirculation);

            log.info(String.format(
                    "[KKopia Economy] 24h update: C=%.4f | rate=%.4f KCoins/K-Crypto | miningMult=×%.2f",
                    totalCirculation, newRate, 1.0 + totalCirculation / 1000.0));

        } catch (Exception e) {
            log.severe("[KKopia Economy] Rate update task failed: " + e.getMessage());
        }
    }
}
