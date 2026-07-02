package com.kcrp.kcrypto.machines;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.config.ConfigManager;
import com.kcrp.kcrypto.database.DatabaseManager;
import com.kcrp.kcrypto.database.DatabaseManager.MachineRow;
import com.kcrp.kcrypto.economy.EconomyManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.Inventory;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * MachineManager – central registry for all active K-Miner machines.
 *
 * <h3>Folia threading notes</h3>
 * <ul>
 *   <li>The {@code machines} map is a {@link ConcurrentHashMap} so it can be
 *       safely iterated from async contexts (e.g., {@link #saveAllMachines()}).</li>
 *   <li>Each machine's tick task is scheduled via {@code RegionScheduler}, which
 *       guarantees the runnable executes on the correct region thread for that chunk.</li>
 *   <li>GUI inventories are created and returned while already on a region thread
 *       (called from {@code PlayerInteractEvent} which fires there).</li>
 * </ul>
 */
public final class MachineManager {

    /** GUI title prefix used to identify machine GUIs in InventoryClickEvent. */
    public static final String GUI_TITLE_PREFIX = "§8[§6K-Miner§8] ";

    private final KcryptoPlugin    plugin;
    private final DatabaseManager db;
    private final EconomyManager  economy;
    private final ConfigManager   cfg;

    /** Map<playerUUID, MachineData> – tracks which machine GUI each player has open. */
    private final Map<UUID, MachineData> openSessions = new ConcurrentHashMap<>();

    /** Map<locationKey, MachineData> */
    private final Map<String, MachineData> machines = new ConcurrentHashMap<>();

    public MachineManager(KcryptoPlugin plugin, DatabaseManager db,
                          EconomyManager economy, ConfigManager cfg) {
        this.plugin  = plugin;
        this.db      = db;
        this.economy = economy;
        this.cfg     = cfg;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Persistence
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Loads all machines from the database and schedules their ticking tasks.
     * Must be called asynchronously (from AsyncScheduler).
     */
    public void loadAllMachines() {
        var rows = db.loadAllMachines();
        int loaded = 0;
        for (MachineRow row : rows) {
            World world = plugin.getServer().getWorld(row.world());
            if (world == null) {
                plugin.getLogger().warning("[KKopia] Machine at " + row.world()
                        + " " + row.x() + "," + row.y() + "," + row.z()
                        + " – world not loaded. Skipping.");
                continue;
            }
            Location loc = new Location(world, row.x(), row.y(), row.z());
            MachineData.NotifMode mode = MachineData.NotifMode.ALL;
            try {
                mode = MachineData.NotifMode.values()[row.notifMode()];
            } catch (Exception ignored) {}

            MachineData data = new MachineData(row.owner(), loc, row.heat(), row.overloadUntil(), mode);
            machines.put(data.getLocationKey(), data);
            scheduleTickTask(data);
            loaded++;
        }
        plugin.getLogger().info("[KKopia] Loaded " + loaded + " machines from database.");
    }

    /**
     * Persists heat/overload for every machine. Called from onDisable on the
     * main thread – but DB writes are already async-safe so this is fine.
     */
    public void saveAllMachines() {
        for (MachineData data : machines.values()) {
            Location loc = data.getLocation();
            if (loc.getWorld() == null) continue;
            db.updateMachineHeat(
                    loc.getWorld().getName(),
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                    data.getHeat(), data.getOverloadUntilMs());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Machine lifecycle
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Registers a newly placed machine, persists it, and starts its tick task.
     *
     * <p>Must be called from the region thread of the placed block's chunk.</p>
     */
    public void placeMachine(UUID owner, Location loc) {
        MachineData data = new MachineData(owner, loc, 0.0, 0L, MachineData.NotifMode.ALL);
        machines.put(data.getLocationKey(), data);
        // Persist asynchronously
        plugin.getServer().getAsyncScheduler().runNow(plugin, t ->
                db.saveMachine(owner,
                        loc.getWorld().getName(),
                        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())
        );
        scheduleTickTask(data);
    }

    /**
     * Removes a machine, cancels its tick task, and deletes it from the DB.
     *
     * <p>Must be called from the region thread of the broken block's chunk.</p>
     *
     * @return the MachineData that was removed, or null if not found
     */
    public MachineData removeMachine(Location loc) {
        String key = MachineData.toKey(loc);
        MachineData data = machines.remove(key);
        if (data == null) return null;

        // Cancel tick task
        cancelTask(data);

        // Delete from DB async
        plugin.getServer().getAsyncScheduler().runNow(plugin, t ->
                db.deleteMachine(
                        loc.getWorld().getName(),
                        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())
        );
        return data;
    }

    /**
     * Returns the MachineData at the given location, or null if none.
     */
    public MachineData getMachine(Location loc) {
        return machines.get(MachineData.toKey(loc));
    }

    /** Returns true if there is a machine at the given location. */
    public boolean isMachine(Location loc) {
        return machines.containsKey(MachineData.toKey(loc));
    }

    /** Returns all active machines. */
    public Collection<MachineData> getAllMachines() {
        return machines.values();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Admin operations
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Force-resets the heat of a machine to 0 and clears overload.
     * Safe to call from any thread (only writes volatiles).
     */
    public boolean forceResetHeat(Location loc) {
        MachineData data = getMachine(loc);
        if (data == null) return false;
        data.setHeat(0.0);
        data.setOverloadUntilMs(0L);
        plugin.getServer().getAsyncScheduler().runNow(plugin, t ->
                db.updateMachineHeat(
                        loc.getWorld().getName(),
                        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                        0.0, 0L)
        );
        return true;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  GUI session tracking
    // ────────────────────────────────────────────────────────────────────────

    /** Records that a player has opened the GUI for a given machine. */
    public void openSession(UUID playerUuid, MachineData data) {
        openSessions.put(playerUuid, data);
    }

    /** Returns the MachineData whose GUI the player currently has open, or null. */
    public MachineData getOpenSession(UUID playerUuid) {
        return openSessions.get(playerUuid);
    }

    /** Removes the open-GUI session for the player. */
    public void clearOpenSession(UUID playerUuid) {
        openSessions.remove(playerUuid);
    }

    /**
     * Finds all players currently viewing this machine's GUI and force-closes
     * their inventory (which triggers the sync and clears the session).
     */
    public void closeSessionsFor(MachineData data) {
        for (var entry : openSessions.entrySet()) {
            if (entry.getValue().equals(data)) {
                org.bukkit.entity.Player p = plugin.getServer().getPlayer(entry.getKey());
                if (p != null) {
                    plugin.getServer().getGlobalRegionScheduler().execute(plugin, p::closeInventory);
                }
            }
        }
    }

    /**
     * Updates the GUI (heat bar and status) for all players viewing this machine.
     * Called by the tick task on the region thread.
     */
    public void updateSessionsFor(MachineData data) {
        for (var entry : openSessions.entrySet()) {
            if (entry.getValue().equals(data)) {
                org.bukkit.entity.Player p = plugin.getServer().getPlayer(entry.getKey());
                if (p != null) {
                    // Update the GUI items on the player's thread
                    plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                        org.bukkit.inventory.InventoryView view = p.getOpenInventory();
                        if (view.getTitle().startsWith(GUI_TITLE_PREFIX)) {
                            double heat = data.getHeat();
                            boolean overload = data.isInOverload();
                            String statusLine = overload
                                    ? "§c⚠ SURCHAUFFE – Refroidissement en cours..."
                                    : (heat >= 80.0 ? "§e⚠ Température critique!" : "§aEn fonctionnement");

                            // Update Info Pane
                            var pane = new org.bukkit.inventory.ItemStack(
                                    overload ? org.bukkit.Material.RED_STAINED_GLASS_PANE
                                             : (heat >= 80 ? org.bukkit.Material.YELLOW_STAINED_GLASS_PANE
                                                           : org.bukkit.Material.GREEN_STAINED_GLASS_PANE));
                            var paneMeta = pane.getItemMeta();
                            paneMeta.setDisplayName(statusLine);
                            paneMeta.setLore(java.util.List.of(
                                    "§7Chaleur: §e" + String.format("%.1f", heat) + "§7/100",
                                    overload ? "§cRefroid dans: " + formatCooldown(data.getOverloadUntilMs()) : ""
                            ));
                            pane.setItemMeta(paneMeta);
                            for (int i = 27; i < 36; i++) view.getTopInventory().setItem(i, pane);

                            // Update Status Icon
                            var statusIcon = new org.bukkit.inventory.ItemStack(
                                    overload ? org.bukkit.Material.BARRIER : org.bukkit.Material.NETHER_STAR);
                            var iconMeta = statusIcon.getItemMeta();
                            iconMeta.setDisplayName(statusLine);
                            iconMeta.setLore(java.util.List.of(
                                    "§7Propriétaire: §e" + data.getOwnerUuid(),
                                    "§7Chaleur: §e" + String.format("%.1f", heat) + "§7/100"
                            ));
                            statusIcon.setItemMeta(iconMeta);
                            view.getTopInventory().setItem(40, statusIcon);

                            // Update Notification Icon
                            var notifIcon = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BELL);
                            var notifMeta = notifIcon.getItemMeta();
                            notifMeta.setDisplayName("§6§lNotifications");
                            notifMeta.setLore(java.util.List.of(
                                    "§7Mode actuel: " + data.getNotifMode().getDisplayName(),
                                    "",
                                    "§e▶ Cliquez pour changer"
                            ));
                            notifIcon.setItemMeta(notifMeta);
                            view.getTopInventory().setItem(44, notifIcon);
                        }
                    });
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  GUI factory
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Builds and returns the virtual inventory GUI for the given machine.
     *
     * <p>The GUI is a 54-slot (6 rows) chest inventory:
     * <ul>
     *   <li>Rows 1–3 (slots 0–26): ore input slots</li>
     *   <li>Row 4 (slots 27–35): separator / info bar</li>
     *   <li>Rows 5–6 (slots 36–53): reserved (heat display + status)</li>
     * </ul>
     *
     * <p>Called from the region thread — inventory creation is thread-safe
     * in Paper/Folia.</p>
     */
    public Inventory buildGUI(MachineData data) {
        double heat = data.getHeat();
        boolean overload = data.isInOverload();

        String heatBar   = buildHeatBar(heat);
        String statusLine = overload
                ? "§c⚠ SURCHAUFFE – Refroidissement en cours..."
                : (heat >= 80.0 ? "§e⚠ Température critique!" : "§aEn fonctionnement");

        String title = GUI_TITLE_PREFIX + heatBar;

        Inventory inv = plugin.getServer().createInventory(null, 54, title);

        // Info pane (row 4, slots 27–35) – glass pane separator
        var pane = new org.bukkit.inventory.ItemStack(
                overload ? org.bukkit.Material.RED_STAINED_GLASS_PANE
                         : (heat >= 80 ? org.bukkit.Material.YELLOW_STAINED_GLASS_PANE
                                       : org.bukkit.Material.GREEN_STAINED_GLASS_PANE));
        var paneMeta = pane.getItemMeta();
        paneMeta.setDisplayName(statusLine);
        paneMeta.setLore(java.util.List.of(
                "§7Chaleur: §e" + String.format("%.1f", heat) + "§7/100",
                overload ? "§cRefroid dans: " + formatCooldown(data.getOverloadUntilMs()) : ""
        ));
        pane.setItemMeta(paneMeta);
        for (int i = 27; i < 36; i++) inv.setItem(i, pane);

        // Ore input slots (0–26) – pre-fill with existing ores
        var slots = data.getOreSlots();
        for (int i = 0; i < MachineData.ORE_SLOT_COUNT; i++) {
            if (slots[i] != null) inv.setItem(i, slots[i].clone());
        }

        // Status icon (slot 40)
        var statusIcon = new org.bukkit.inventory.ItemStack(
                overload ? org.bukkit.Material.BARRIER : org.bukkit.Material.NETHER_STAR);
        var iconMeta = statusIcon.getItemMeta();
        iconMeta.setDisplayName(statusLine);
        iconMeta.setLore(java.util.List.of(
                "§7Propriétaire: §e" + data.getOwnerUuid(),
                "§7Chaleur: §e" + String.format("%.1f", heat) + "§7/100"
        ));
        statusIcon.setItemMeta(iconMeta);
        inv.setItem(40, statusIcon);

        // Notification Settings button (slot 44)
        var notifIcon = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BELL);
        var notifMeta = notifIcon.getItemMeta();
        notifMeta.setDisplayName("§6§lNotifications");
        notifMeta.setLore(java.util.List.of(
                "§7Mode actuel: " + data.getNotifMode().getDisplayName(),
                "",
                "§e▶ Cliquez pour changer"
        ));
        notifIcon.setItemMeta(notifMeta);
        inv.setItem(44, notifIcon);

        return inv;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Cleanup
    // ────────────────────────────────────────────────────────────────────────

    /** Cancels all active ticking tasks. Called from onDisable. */
    public void cancelAllTasks() {
        for (MachineData data : machines.values()) {
            cancelTask(data);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Schedules the RegionScheduler tick task for a machine.
     * RegionScheduler guarantees execution on the chunk-owning thread.
     */
    private void scheduleTickTask(MachineData data) {
        Location loc = data.getLocation();
        if (loc.getWorld() == null) return;

        long periodTicks = cfg.getTickIntervalSeconds() * 20L; // seconds → ticks

        var task = plugin.getServer().getRegionScheduler()
                .runAtFixedRate(plugin, loc, tickTask -> {
                    MachineTickTask runner = new MachineTickTask(
                            plugin, data, db, economy, cfg);
                    runner.run();
                }, periodTicks, periodTicks);

        data.setTickTask(task);
    }

    private void cancelTask(MachineData data) {
        Object taskObj = data.getTickTask();
        if (taskObj instanceof ScheduledTask st) {
            st.cancel();
        }
    }

    private String buildHeatBar(double heat) {
        int filled = (int) Math.round(heat / 10.0); // 0–10 blocks
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i < filled) sb.append("§c▮");
            else           sb.append("§8▯");
        }
        return sb.toString();
    }

    private String formatCooldown(long untilMs) {
        long remaining = (untilMs - System.currentTimeMillis()) / 1000L;
        if (remaining <= 0) return "0s";
        long min = remaining / 60;
        long sec = remaining % 60;
        return min > 0 ? min + "m " + sec + "s" : sec + "s";
    }
}
