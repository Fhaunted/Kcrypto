package com.kcrp.kcrypto.listeners;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.economy.EconomyManager;
import com.kcrp.kcrypto.machines.MachineData;
import com.kcrp.kcrypto.machines.MachineManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * MachineGUIListener – governs all interactions inside the K-Miner virtual GUI.
 *
 * <h3>Slot zones (54-slot chest)</h3>
 * <ul>
 *   <li>Slots  0–26: ore input zone — only accepted ores allowed.</li>
 *   <li>Slots 27–53: info/status zone — always blocked.</li>
 * </ul>
 *
 * <h3>Close sync</h3>
 * On {@link InventoryCloseEvent} the contents of slots 0–26 are written back
 * into the corresponding {@link MachineData#getOreSlots()} array so the tick
 * task always reads current player-inserted ores.
 *
 * <p>Events fire on the region thread owning the player's chunk in Folia —
 * no scheduler hops needed.</p>
 */
public final class MachineGUIListener implements Listener {

    private final KcryptoPlugin   plugin;
    private final MachineManager machineManager;
    private final EconomyManager economy;

    public MachineGUIListener(KcryptoPlugin plugin, MachineManager machineManager,
                               EconomyManager economy) {
        this.plugin         = plugin;
        this.machineManager = machineManager;
        this.economy        = economy;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Click gating
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // ── Track GUI: fully read-only ───────────────────────────────────
        String title = event.getView().getTitle();
        if (title.equals("§8[§6K-Crypto Track§8]")) {
            event.setCancelled(true);
            return;
        }

        // Identify machine GUI by open-session map (most reliable)
        MachineData data = machineManager.getOpenSession(player.getUniqueId());
        if (data == null) return;

        Inventory topInv = event.getView().getTopInventory();
        Inventory clickedInv = event.getClickedInventory();

        if (clickedInv == null) {
            event.setCancelled(true);
            return;
        }

        // Block dangerous click actions that could bypass inventory checks
        switch (event.getClick()) {
            case NUMBER_KEY:
            case SWAP_OFFHAND:
            case DROP:
            case CONTROL_DROP:
            case DOUBLE_CLICK:
                event.setCancelled(true);
                return;
            default:
                break;
        }

        // ── Click in top (machine) inventory ──────────────────────────────
        if (clickedInv.equals(topInv)) {
            int slot = event.getSlot();

            if (slot == 44) {
                // Notification mode toggle button
                event.setCancelled(true);
                MachineData.NotifMode newMode = data.getNotifMode().next();
                data.setNotifMode(newMode);
                player.sendMessage("§8[§6K-Miner§8] §eNotifications: " + newMode.getDisplayName());

                // Persist to DB async
                org.bukkit.Location loc = data.getLocation();
                plugin.getServer().getAsyncScheduler().runNow(plugin, t ->
                        plugin.getDatabaseManager().updateMachineNotifMode(
                                loc.getWorld().getName(),
                                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                                newMode.ordinal()));
                return;
            }

            if (slot >= 27) {
                // Info/status zone — always blocked
                event.setCancelled(true);
                return;
            }

            // Ore zone: validate what the player is trying to place
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                if (!EconomyManager.isAcceptedOre(cursor.getType())) {
                    event.setCancelled(true);
                    player.sendMessage("§8[§6K-Miner§8] §cSeuls les minerais acceptés peuvent être insérés.");
                }
            }
            // Picking items up from ore zone is always allowed

        } else {
            // ── Click in player's bottom inventory (shift-click into machine) ─
            if (event.isShiftClick()) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked == null || !EconomyManager.isAcceptedOre(clicked.getType())) {
                    event.setCancelled(true);
                    player.sendMessage("§8[§6K-Miner§8] §cSeuls les minerais acceptés peuvent être insérés.");
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Close – sync GUI contents → MachineData ore array
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        MachineData data = machineManager.getOpenSession(player.getUniqueId());
        if (data == null) return;

        Inventory topInv = event.getView().getTopInventory();
        ItemStack[] oreSlots = data.getOreSlots();

        for (int i = 0; i < MachineData.ORE_SLOT_COUNT; i++) {
            ItemStack item = topInv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                oreSlots[i] = item.clone();
            } else {
                oreSlots[i] = null;
            }
        }

        machineManager.clearOpenSession(player.getUniqueId());
    }
}
