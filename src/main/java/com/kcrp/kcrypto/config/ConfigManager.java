package com.kcrp.kcrypto.config;

import com.kcrp.kcrypto.KcryptoPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed, cached wrapper around Bukkit's FileConfiguration.
 * All values are read once and cached for thread-safe access
 * from async scheduler tasks.
 *
 * <p>Call {@link #reload()} to re-read the config.yml from disk
 * and refresh all cached values.</p>
 */
public final class ConfigManager {

    private final KcryptoPlugin plugin;

    // Database
    private volatile String dbHost;
    private volatile int    dbPort;
    private volatile String dbName;
    private volatile String dbUser;
    private volatile String dbPassword;
    private volatile String economyTable;
    private volatile String economyColumn;
    private volatile int    poolSize;

    // Economy
    private volatile double  laundererTaxRate;
    private volatile double  rateFloor;
    private volatile double  baseRate;
    private volatile double  rateDecayConstant;

    // Machine
    private volatile long    tickIntervalSeconds;
    private volatile long    overloadCooldownSeconds;
    private volatile double  passiveCoolingRate;
    private volatile int     raritySkipMultiplier;

    // Messages
    private volatile String prefix;
    private volatile String msgNoPermission;
    private volatile String msgLaundererDenied;

    public ConfigManager(KcryptoPlugin plugin) {
        this.plugin = plugin;
        loadValues();
    }

    /**
     * Reloads config.yml from disk and refreshes all cached values.
     * Safe to call from any thread; Bukkit's reloadConfig() is
     * synchronised internally.
     */
    public void reload() {
        plugin.reloadConfig();
        loadValues();
    }

    private void loadValues() {
        FileConfiguration cfg = plugin.getConfig();

        dbHost     = cfg.getString("database.host",     "localhost");
        dbPort     = cfg.getInt   ("database.port",     3306);
        dbName     = cfg.getString("database.name",     "kcrp_main");
        dbUser     = cfg.getString("database.user",     "root");
        dbPassword = cfg.getString("database.password", "password");
        economyTable  = cfg.getString("database.economy-table",  "essential_wallets");
        economyColumn = cfg.getString("database.economy-column", "money");
        poolSize   = cfg.getInt   ("database.pool-size", 10);

        laundererTaxRate       = cfg.getDouble("economy.launderer-tax-rate", 5.0);
        rateFloor              = cfg.getDouble("economy.rate-floor",         0.01);
        baseRate               = cfg.getDouble("economy.base-rate",          3.0);
        rateDecayConstant      = cfg.getDouble("economy.rate-decay-constant", 0.0004);

        tickIntervalSeconds     = cfg.getLong("machine.tick-interval-seconds",    30L);
        overloadCooldownSeconds = cfg.getLong("machine.overload-cooldown-seconds", 1800L);
        passiveCoolingRate      = cfg.getDouble("machine.passive-cooling-rate",   2.0);
        raritySkipMultiplier    = cfg.getInt("machine.rarity-skip-multiplier",    4);

        prefix              = color(cfg.getString("messages.prefix",           "&8[&6KKopia&8] &r"));
        msgNoPermission     = color(cfg.getString("messages.no-permission",    "&cVous n'avez pas la permission."));
        msgLaundererDenied  = color(cfg.getString("messages.launderer-denied", "&7Cet individu ne souhaite pas vous parler."));
    }

    // ── Database ────────────────────────────────────────────────────────────

    public String getDbHost()        { return dbHost;        }
    public int    getDbPort()        { return dbPort;        }
    public String getDbName()        { return dbName;        }
    public String getDbUser()        { return dbUser;        }
    public String getDbPassword()    { return dbPassword;    }
    public String getEconomyTable()  { return economyTable;  }
    public String getEconomyColumn() { return economyColumn; }
    public int    getPoolSize()      { return poolSize;      }

    // ── Economy ─────────────────────────────────────────────────────────────

    /** Tax rate as a fraction, e.g. 5.0 → 0.05. */
    public double getLaundererTaxFraction() { return laundererTaxRate / 100.0; }
    public double getRateFloor()            { return rateFloor; }
    public double getBaseRate()             { return baseRate;  }
    /** The 'k' constant in the formula: Rate = baseRate / (1 + C × k). */
    public double getRateDecayConstant()    { return rateDecayConstant; }

    // ── Machine ─────────────────────────────────────────────────────────────

    public long   getTickIntervalSeconds()     { return tickIntervalSeconds;     }
    public long   getOverloadCooldownSeconds() { return overloadCooldownSeconds; }
    /** Heat points reduced per tick when the machine has no ores. Default 2.0. */
    public double getPassiveCoolingRate()       { return passiveCoolingRate;      }
    public int    getRaritySkipMultiplier()     { return raritySkipMultiplier;    }

    // ── Messages ────────────────────────────────────────────────────────────

    public String getPrefix()             { return prefix;             }
    public String getMsgNoPermission()    { return msgNoPermission;    }
    public String getMsgLaundererDenied() { return msgLaundererDenied; }

    /** Convenience: format a message with the plugin prefix. */
    public String fmt(String message) {
        return prefix + message;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
