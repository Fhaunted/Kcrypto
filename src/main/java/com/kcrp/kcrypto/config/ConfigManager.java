package com.kcrp.kcrypto.config;

import com.kcrp.kcrypto.KcryptoPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed, cached wrapper around Bukkit's FileConfiguration.
 * All values are read once and cached for thread-safe access
 * from async scheduler tasks.
 */
public final class ConfigManager {

    private final KcryptoPlugin plugin;

    // Database
    private final String dbHost;
    private final int    dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPassword;
    private final String economyTable;
    private final String economyColumn;
    private final int    poolSize;

    // Economy
    private final double  laundererTaxRate;
    private final double  rateFloor;
    private final double  baseRate;

    // Machine
    private final long    tickIntervalSeconds;
    private final long    overloadCooldownSeconds;

    // Messages
    private final String prefix;
    private final String msgNoPermission;
    private final String msgLaundererDenied;

    public ConfigManager(KcryptoPlugin plugin) {
        this.plugin = plugin;
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
        baseRate               = cfg.getDouble("economy.base-rate",          100.0);

        tickIntervalSeconds     = cfg.getLong("machine.tick-interval-seconds",    30L);
        overloadCooldownSeconds = cfg.getLong("machine.overload-cooldown-seconds", 1800L);

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

    // ── Machine ─────────────────────────────────────────────────────────────

    public long getTickIntervalSeconds()     { return tickIntervalSeconds;     }
    public long getOverloadCooldownSeconds() { return overloadCooldownSeconds; }

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
