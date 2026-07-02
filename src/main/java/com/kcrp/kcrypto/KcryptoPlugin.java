package com.kcrp.kcrypto;

import com.kcrp.kcrypto.commands.KcryptoCommand;
import com.kcrp.kcrypto.config.ConfigManager;
import com.kcrp.kcrypto.database.DatabaseManager;
import com.kcrp.kcrypto.economy.EconomyManager;
import com.kcrp.kcrypto.economy.RateTask;
import com.kcrp.kcrypto.items.MinerItem;
import com.kcrp.kcrypto.listeners.LaundererListener;
import com.kcrp.kcrypto.listeners.MachineGUIListener;
import com.kcrp.kcrypto.listeners.MachineListener;
import com.kcrp.kcrypto.machines.MachineManager;
import com.kcrp.kcrypto.villager.LaundererManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * KcryptoPlugin – main entry point.
 *
 * <p>Boot order (all async-safe for Folia):
 * <ol>
 *   <li>Load configuration</li>
 *   <li>Initialise database (async) – creates tables if absent</li>
 *   <li>Hook Vault economy</li>
 *   <li>Build managers (economy, machines, villager)</li>
 *   <li>Register listeners and commands</li>
 *   <li>Schedule the 24-h rate task</li>
 * </ol>
 */
public final class KcryptoPlugin extends JavaPlugin {

    private ConfigManager   configManager;
    private DatabaseManager databaseManager;
    private EconomyManager  economyManager;
    private MachineManager  machineManager;
    private LaundererManager laundererManager;

    // ────────────────────────────────────────────────────────────────────────
    //  Plugin lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        // 1. Configuration
        saveDefaultConfig();
        configManager = new ConfigManager(this);

        // 2. Database – open pool synchronously, create tables asynchronously
        databaseManager = new DatabaseManager(this, configManager);
        if (!databaseManager.openPool()) {
            getLogger().severe("Failed to open database pool – disabling KKopia.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Async table creation – non-blocking for Folia
        getServer().getAsyncScheduler().runNow(this, task -> databaseManager.initTables());

        // 3. Managers
        economyManager   = new EconomyManager(this, databaseManager, configManager);
        machineManager   = new MachineManager(this, databaseManager, economyManager, configManager);
        laundererManager = new LaundererManager(this, databaseManager, economyManager, configManager);

        // Register the custom crafting recipe for the K-Miner item
        MinerItem.registerRecipe(this);

        // 4. Listeners
        var pm = getServer().getPluginManager();
        pm.registerEvents(new MachineListener(this, machineManager, configManager), this);
        pm.registerEvents(new MachineGUIListener(this, machineManager, economyManager), this);
        pm.registerEvents(new LaundererListener(this, laundererManager, economyManager,
                databaseManager, configManager), this);

        // 5. Commands
        var cmdKcrypto = getCommand("kcrypto");
        var cmdCrypto = getCommand("crypto");
        var handler = new KcryptoCommand(this, machineManager, economyManager,
                databaseManager, laundererManager, configManager);
        if (cmdKcrypto != null) {
            cmdKcrypto.setExecutor(handler);
            cmdKcrypto.setTabCompleter(handler);
        }
        if (cmdCrypto != null) {
            cmdCrypto.setExecutor(handler);
            cmdCrypto.setTabCompleter(handler);
        }

        // 6. Load machines from DB (async, then schedule their tickers)
        getServer().getAsyncScheduler().runNow(this, task -> machineManager.loadAllMachines());

        // 7. Schedule the 24-hour economy rate update task
        // Initial run after 10 s to let the DB settle, then every 24 h
        getServer().getAsyncScheduler().runDelayed(this,
                task -> new RateTask(this, databaseManager, economyManager,
                        laundererManager, configManager).run(),
                10, TimeUnit.SECONDS);

        getServer().getAsyncScheduler().runAtFixedRate(this,
                task -> new RateTask(this, databaseManager, economyManager,
                        laundererManager, configManager).run(),
                24, 24, TimeUnit.HOURS);

        getLogger().info("KKopia v" + getDescription().getVersion() + " enabled successfully.");
    }

    @Override
    public void onDisable() {
        // Stop all machine ticking tasks
        if (machineManager != null) {
            machineManager.cancelAllTasks();
        }
        // Persist any dirty machine states before shutdown
        if (machineManager != null) {
            machineManager.saveAllMachines();
        }
        // Close DB pool last
        if (databaseManager != null) {
            databaseManager.closePool();
        }
        getLogger().info("KKopia disabled – all tasks cancelled and data persisted.");
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Public accessors (for cross-manager use)
    // ────────────────────────────────────────────────────────────────────────

    public ConfigManager   getConfigManager()    { return configManager;   }
    public DatabaseManager getDatabaseManager()  { return databaseManager; }
    public EconomyManager  getEconomyManager()   { return economyManager;  }
    public MachineManager  getMachineManager()   { return machineManager;  }
    public LaundererManager getLaundererManager(){ return laundererManager;}
}
