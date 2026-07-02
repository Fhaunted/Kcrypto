package com.kcrp.kcrypto.listeners;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.config.ConfigManager;
import com.kcrp.kcrypto.items.MinerItem;
import com.kcrp.kcrypto.machines.MachineData;
import com.kcrp.kcrypto.machines.MachineManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * MachineListener – handles the physical lifecycle of the K-Miner block.
 *
 * <ul>
 *   <li>{@link BlockPlaceEvent} – registers the machine when the item is placed.</li>
 *   <li>{@link BlockBreakEvent} – deregisters the machine, drops contained ores,
 *       cancels the ticking task.</li>
 *   <li>{@link PlayerInteractEvent} – opens the machine GUI on right-click.</li>
 * </ul>
 *
 * <p>All these events fire on the region thread owning the relevant chunk, so
 * world/block operations here are fully Folia-safe.</p>
 */
public final class MachineListener implements Listener {

    private final KcryptoPlugin   plugin;
    private final MachineManager machineManager;
    private final ConfigManager  cfg;

    public MachineListener(KcryptoPlugin plugin, MachineManager machineManager,
                           ConfigManager cfg) {
        this.plugin         = plugin;
        this.machineManager = machineManager;
        this.cfg            = cfg;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Placement
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        if (!MinerItem.isMiner(plugin, hand)) return;

        Player   player = event.getPlayer();
        Location loc    = event.getBlock().getLocation();

        machineManager.placeMachine(player.getUniqueId(), loc);

        player.sendMessage(cfg.fmt("§aK-Miner déployé! Faites un §eclic-droit §apour ouvrir l'interface."));
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Breaking
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block    block  = event.getBlock();
        Location loc    = block.getLocation();

        if (!machineManager.isMachine(loc)) return;

        Player player = event.getPlayer();

        // Only the owner or an admin can break the machine
        MachineData data = machineManager.getMachine(loc);
        if (data == null) return;

        if (!data.getOwnerUuid().equals(player.getUniqueId())
                && !player.hasPermission("kopia.admin")) {
            event.setCancelled(true);
            player.sendMessage(cfg.fmt("§cSeul le propriétaire peut détruire ce K-Miner."));
            return;
        }

        // Prevent automatic ore drops handled by Bukkit – we drop them manually
        event.setDropItems(false);

        // Remove machine and get its data
        MachineData removed = machineManager.removeMachine(loc);
        if (removed == null) return;

        // Force close GUI for anyone currently viewing this machine
        machineManager.closeSessionsFor(removed);

        // Drop all inserted ores at the broken block's location
        dropOres(removed, loc);

        // Drop the K-Miner item itself (unless creative)
        if (player.getGameMode() != GameMode.CREATIVE) {
            block.getWorld().dropItemNaturally(loc, MinerItem.build(plugin));
        }

        player.sendMessage(cfg.fmt("§eK-Miner retiré – vos minerais ont été restitués."));
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Right-click → open GUI
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        Location loc = block.getLocation();
        if (!machineManager.isMachine(loc)) return;

        event.setCancelled(true); // prevent any default right-click behaviour

        Player player = event.getPlayer();
        if (!player.hasPermission("kcrypto.member")) {
            player.sendMessage(cfg.getMsgNoPermission());
            return;
        }

        MachineData data = machineManager.getMachine(loc);
        if (data == null) return;

        // Force close any existing inventory to prevent the old InventoryCloseEvent
        // from syncing its contents into this *new* machine session.
        player.closeInventory();

        // Register session BEFORE opening so the new close event can sync ores
        machineManager.openSession(player.getUniqueId(), data);
        // Open the GUI (safe – still on region thread)
        player.openInventory(machineManager.buildGUI(data));
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Helper
    // ────────────────────────────────────────────────────────────────────────

    private void dropOres(MachineData data, Location loc) {
        for (ItemStack ore : data.getOreSlots()) {
            if (ore != null && ore.getType() != Material.AIR && ore.getAmount() > 0) {
                loc.getWorld().dropItemNaturally(loc, ore);
            }
        }
    }
}
