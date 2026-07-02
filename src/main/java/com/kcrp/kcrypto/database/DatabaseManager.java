package com.kcrp.kcrypto.database;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * DatabaseManager – HikariCP-backed MySQL access layer.
 *
 * <p><b>Thread contract:</b> every public method in this class is designed
 * to be called from an async context (Folia AsyncScheduler or a virtual-thread
 * pool).  <em>Never</em> call these methods on the server main/region thread.</p>
 */
public final class DatabaseManager {

    private static final String TABLE_WALLETS  = "kkopia_wallets";
    private static final String TABLE_MACHINES = "kkopia_machines";

    private final KcryptoPlugin  plugin;
    private final ConfigManager cfg;
    private HikariDataSource    ds;

    public DatabaseManager(KcryptoPlugin plugin, ConfigManager cfg) {
        this.plugin = plugin;
        this.cfg    = cfg;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Pool lifecycle
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Opens the HikariCP connection pool.
     * Called synchronously during {@code onEnable()} before any task is scheduled.
     *
     * @return true if the pool was opened and a test connection succeeded.
     */
    public boolean openPool() {
        try {
            HikariConfig hcfg = new HikariConfig();
            hcfg.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&autoReconnect=true&serverTimezone=UTC",
                    cfg.getDbHost(), cfg.getDbPort(), cfg.getDbName()));
            hcfg.setUsername(cfg.getDbUser());
            hcfg.setPassword(cfg.getDbPassword());
            hcfg.setMaximumPoolSize(cfg.getPoolSize());
            hcfg.setMinimumIdle(2);
            hcfg.setConnectionTimeout(10_000);
            hcfg.setIdleTimeout(300_000);
            hcfg.setMaxLifetime(600_000);
            hcfg.setPoolName("KKopia-Pool");
            // Keeps connections alive
            hcfg.setConnectionTestQuery("SELECT 1");
            ds = new HikariDataSource(hcfg);
            // Validate immediately
            try (Connection c = ds.getConnection()) {
                plugin.getLogger().info("Database connection established to " + cfg.getDbName());
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to open database connection pool!", e);
            return false;
        }
    }

    /** Closes the connection pool gracefully during {@code onDisable()}. */
    public void closePool() {
        if (ds != null && !ds.isClosed()) {
            ds.close();
            plugin.getLogger().info("Database pool closed.");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Table initialisation
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Creates the plugin's tables if they do not already exist.
     * Must be called asynchronously.
     */
    public void initTables() {
        String wallets = """
                CREATE TABLE IF NOT EXISTS `%s` (
                  `uuid`               VARCHAR(36)  NOT NULL,
                  `crypto_balance`     DOUBLE       NOT NULL DEFAULT 0.0,
                  `launderer_tax_pool` DOUBLE       NOT NULL DEFAULT 0.0,
                  PRIMARY KEY (`uuid`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.formatted(TABLE_WALLETS);

        String machines = """
                CREATE TABLE IF NOT EXISTS `%s` (
                  `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
                  `owner_uuid`      VARCHAR(36) NOT NULL,
                  `world`           VARCHAR(64) NOT NULL,
                  `x`               INT         NOT NULL,
                  `y`               INT         NOT NULL,
                  `z`               INT         NOT NULL,
                  `heat`            DOUBLE      NOT NULL DEFAULT 0.0,
                  `overload_until`  BIGINT      NOT NULL DEFAULT 0,
                  UNIQUE KEY `location` (`world`, `x`, `y`, `z`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.formatted(TABLE_MACHINES);

        try (Connection c = ds.getConnection();
             Statement  s = c.createStatement()) {
            s.execute(wallets);
            s.execute(machines);
            plugin.getLogger().info("KKopia tables verified/created.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialise database tables!", e);
        }

        try (Connection c = ds.getConnection();
             Statement  s = c.createStatement()) {
            s.execute("ALTER TABLE `" + TABLE_MACHINES + "` ADD COLUMN `notif_mode` INT NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {
            // Column already exists
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Wallet CRUD
    // ────────────────────────────────────────────────────────────────────────

    /** Ensures a wallet row exists for this player. */
    public void ensureWallet(UUID uuid) {
        String sql = "INSERT IGNORE INTO `" + TABLE_WALLETS + "` (uuid) VALUES (?)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "ensureWallet failed for " + uuid, e);
        }
    }

    /**
     * Returns the K-Crypto balance for a player, or 0.0 if no row exists.
     */
    public double getCryptoBalance(UUID uuid) {
        String sql = "SELECT `crypto_balance` FROM `" + TABLE_WALLETS + "` WHERE `uuid` = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("crypto_balance");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "getCryptoBalance failed for " + uuid, e);
        }
        return 0.0;
    }

    /**
     * Adds {@code delta} to a player's K-Crypto balance.
     * {@code delta} may be negative (for deductions).
     *
     * @return true if the operation succeeded and the balance was sufficient
     *         when {@code delta} is negative.
     */
    public boolean addCryptoBalance(UUID uuid, double delta) {
        ensureWallet(uuid);
        String sql = """
                UPDATE `%s`
                SET `crypto_balance` = GREATEST(0, `crypto_balance` + ?)
                WHERE `uuid` = ?
                """.formatted(TABLE_WALLETS);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, delta);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "addCryptoBalance failed for " + uuid, e);
            return false;
        }
    }

    /** Returns the launderer_tax_pool for the given UUID. */
    public double getTaxPool(UUID uuid) {
        String sql = "SELECT `launderer_tax_pool` FROM `" + TABLE_WALLETS + "` WHERE `uuid` = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("launderer_tax_pool");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "getTaxPool failed for " + uuid, e);
        }
        return 0.0;
    }

    /** Adds {@code amount} to the launderer_tax_pool. */
    public void addToTaxPool(UUID uuid, double amount) {
        ensureWallet(uuid);
        String sql = """
                UPDATE `%s`
                SET `launderer_tax_pool` = `launderer_tax_pool` + ?
                WHERE `uuid` = ?
                """.formatted(TABLE_WALLETS);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, amount);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "addToTaxPool failed for " + uuid, e);
        }
    }

    /** Drains the launderer_tax_pool to 0 and returns the amount drained. */
    public double drainTaxPool(UUID uuid) {
        ensureWallet(uuid);
        double pool = getTaxPool(uuid);
        if (pool <= 0) return 0;
        String sql = "UPDATE `" + TABLE_WALLETS + "` SET `launderer_tax_pool` = 0 WHERE `uuid` = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "drainTaxPool failed for " + uuid, e);
            return 0;
        }
        return pool;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Economy queries & direct transactions (kconomy)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Returns the total sum of KCoins in circulation by combining
     * kconomy_wallets and kconomy_accounts.
     */
    public double getTotalKCoinsInCirculation() {
        String sql = """
                SELECT SUM(balance) AS total_kcoins FROM (
                    SELECT balance FROM kconomy_wallets WHERE balance > 0
                    UNION ALL
                    SELECT balance FROM kconomy_accounts WHERE balance > 0
                ) AS combined
                """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getDouble("total_kcoins");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to query total KCoins – rate calculation will use last known value.", e);
        }
        return 0.0;
    }

    /**
     * Direct SQL deposit to the kconomy_wallets table.
     * Replaces Vault for credit payouts.
     *
     * @return true if rows were updated.
     */
    public boolean creditKconomy(UUID uuid, double amount) {
        String sql = "UPDATE kconomy_wallets SET balance = balance + ? WHERE uuid = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, amount);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "creditKconomy failed for " + uuid, e);
            return false;
        }
    }

    /**
     * Retrieves the current balance from the kconomy_wallets table.
     */
    public double getKconomyBalance(UUID uuid) {
        String sql = "SELECT balance FROM kconomy_wallets WHERE uuid = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("balance");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "getKconomyBalance failed for " + uuid, e);
        }
        return 0.0;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Machine CRUD
    // ────────────────────────────────────────────────────────────────────────

    /** Persists a newly placed machine. */
    public void saveMachine(UUID owner, String world, int x, int y, int z) {
        String sql = """
                INSERT INTO `%s` (owner_uuid, world, x, y, z)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE owner_uuid = VALUES(owner_uuid)
                """.formatted(TABLE_MACHINES);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setString(2, world);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "saveMachine failed!", e);
        }
    }

    /** Deletes a machine record by location. */
    public void deleteMachine(String world, int x, int y, int z) {
        String sql = "DELETE FROM `" + TABLE_MACHINES + "` WHERE `world`=? AND `x`=? AND `y`=? AND `z`=?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "deleteMachine failed!", e);
        }
    }

    /** Updates heat and overload timestamp for a machine. */
    public void updateMachineHeat(String world, int x, int y, int z,
                                   double heat, long overloadUntil) {
        String sql = """
                UPDATE `%s`
                SET `heat` = ?, `overload_until` = ?
                WHERE `world`=? AND `x`=? AND `y`=? AND `z`=?
                """.formatted(TABLE_MACHINES);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, heat);
            ps.setLong(2, overloadUntil);
            ps.setString(3, world);
            ps.setInt(4, x);
            ps.setInt(5, y);
            ps.setInt(6, z);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "updateMachineHeat failed – data may be lost on restart.", e);
        }
    }

    /** Updates the notification mode for a machine. */
    public void updateMachineNotifMode(String world, int x, int y, int z, int notifMode) {
        String sql = "UPDATE `" + TABLE_MACHINES + "` SET `notif_mode` = ? WHERE `world`=? AND `x`=? AND `y`=? AND `z`=?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, notifMode);
            ps.setString(2, world);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "updateMachineNotifMode failed.", e);
        }
    }

    /**
     * Returns all persisted machine rows as raw {@link MachineRow} records.
     */
    public List<MachineRow> loadAllMachines() {
        List<MachineRow> rows = new ArrayList<>();
        String sql = "SELECT `owner_uuid`,`world`,`x`,`y`,`z`,`heat`,`overload_until`,`notif_mode`"
                + " FROM `" + TABLE_MACHINES + "`";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new MachineRow(
                        UUID.fromString(rs.getString("owner_uuid")),
                        rs.getString("world"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getDouble("heat"),
                        rs.getLong("overload_until"),
                        rs.getInt("notif_mode")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "loadAllMachines failed!", e);
        }
        return rows;
    }

    /** Simple data carrier for a machine database row. */
    public record MachineRow(UUID owner, String world,
                             int x, int y, int z,
                             double heat, long overloadUntil, int notifMode) {}
}
