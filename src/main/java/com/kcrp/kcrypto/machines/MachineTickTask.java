package com.kcrp.kcrypto.machines;

import com.kcrp.kcrypto.KcryptoPlugin;
import com.kcrp.kcrypto.config.ConfigManager;
import com.kcrp.kcrypto.database.DatabaseManager;
import com.kcrp.kcrypto.economy.EconomyManager;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * MachineTickTask – the per-machine Runnable executed by Folia's
 * {@code RegionScheduler} at a fixed interval.
 *
 * <h3>Tick logic</h3>
 * <ol>
 *   <li>If machine is in overload: check if cooldown has expired; if not, return early.</li>
 *   <li>Consume one ore unit from the virtual inventory.</li>
 *   <li>Award K-Crypto to the owner's wallet (async DB write).</li>
 *   <li>Add heat. If heat ≥ 100 → trigger overload: smoke particles, hiss sound,
 *       set overload timestamp, persist to DB.</li>
 *   <li>Persist heat to DB every tick (async).</li>
 * </ol>
 *
 * <p><b>Threading:</b> The {@code run()} body executes on the region thread
 * owning the machine's chunk (safe for world/block access). The DB write is
 * dispatched to {@code AsyncScheduler}.</p>
 */
public final class MachineTickTask implements Runnable {

    private final KcryptoPlugin    plugin;
    private final MachineData     data;
    private final DatabaseManager db;
    private final EconomyManager  economy;
    private final ConfigManager   cfg;

    public MachineTickTask(KcryptoPlugin plugin, MachineData data,
                           DatabaseManager db, EconomyManager economy,
                           ConfigManager cfg) {
        this.plugin  = plugin;
        this.data    = data;
        this.db      = db;
        this.economy = economy;
        this.cfg     = cfg;
    }

    @Override
    public void run() {
        Location loc = data.getLocation();

        // ── 1. Overload check ──────────────────────────────────────────────
        if (data.isInOverload()) {
            return; // still cooling down
        }

        // If the machine just finished overload, reset heat and resume
        if (data.getOverloadUntilMs() > 0 && !data.isInOverload()) {
            data.setHeat(0.0);
            data.setOverloadUntilMs(0L);
            persistHeatAsync();
        }

        // ── 2. No ores? Nothing to do ──────────────────────────────────────
        if (!data.hasOres()) {
            return;
        }

        // ── 3. Consume one ore ────────────────────────────────────────────
        var ore = data.consumeOneOre();
        if (ore == null) return;

        double yield = EconomyManager.getOreYield(ore.getType());
        double heat  = EconomyManager.getOreHeat(ore.getType());

        // ── 4. Credit K-Crypto asynchronously ────────────────────────────
        UUID  owner      = data.getOwnerUuid();
        double finalYield = yield;
        plugin.getServer().getAsyncScheduler().runNow(plugin, asyncTask -> {
            db.addCryptoBalance(owner, finalYield);

            // Notify based on NotifMode
            if (data.getNotifMode() == MachineData.NotifMode.ALL) {
                var ownerPlayer = plugin.getServer().getPlayer(owner);
                if (ownerPlayer != null) {
                    plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                            ownerPlayer.sendMessage(cfg.getPrefix()
                                    + "\u00a7a+" + String.format("%.2f", finalYield)
                                    + " K-Crypto \u00a78(\u00a7e" + ore.getType().name() + "\u00a78)")
                    );
                }
            }
        });

        // If FINISHED mode and ores just ran out, notify once
        if (data.getNotifMode() == MachineData.NotifMode.FINISHED && !data.hasOres()) {
            var ownerPlayer = plugin.getServer().getPlayer(owner);
            if (ownerPlayer != null) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                        ownerPlayer.sendMessage(cfg.getPrefix()
                                + "\u00a7eVotre K-Miner a épuisé tous ses stocks de minerais !")
                );
            }
        }

        // ── 5. Apply heat ─────────────────────────────────────────────────
        org.bukkit.block.Block block = loc.getBlock();
        
        // If a solid block is above, the machine heats up more (2x heat)
        org.bukkit.block.Block above = block.getRelative(org.bukkit.block.BlockFace.UP);
        if (above.getType().isSolid()) {
            heat *= 2.0;
        }

        // Blue ice around the machine cools it down
        int blueIceCount = 0;
        org.bukkit.block.BlockFace[] sides = {
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST
        };
        for (org.bukkit.block.BlockFace face : sides) {
            if (block.getRelative(face).getType() == org.bukkit.Material.BLUE_ICE) {
                blueIceCount++;
            }
        }
        
        // Each blue ice block reduces heat generation by 2.5 points
        if (blueIceCount > 0) {
            heat -= (blueIceCount * 2.5);
        }

        double newHeat = data.getHeat() + heat;
        if (newHeat < 0) newHeat = 0.0;
        data.setHeat(newHeat);

        // ── 6. Overload trigger ────────────────────────────────────────────
        if (data.getHeat() >= MachineData.MAX_HEAT) {
            long overloadEnd = System.currentTimeMillis()
                    + (cfg.getOverloadCooldownSeconds() * 1_000L);
            data.setOverloadUntilMs(overloadEnd);
            data.setHeat(MachineData.MAX_HEAT);

            // Visual / audio feedback on the region thread (safe here)
            if (loc.getWorld() != null) {
                loc.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc.clone().add(0.5, 1.0, 0.5),
                        30, 0.3, 0.5, 0.3, 0.05);
                loc.getWorld().playSound(loc, Sound.BLOCK_LAVA_POP, 1.0f, 0.5f);
            }

            plugin.getLogger().info("[KKopia] Machine at " + MachineData.toKey(loc)
                    + " entered overload. Cooldown: " + cfg.getOverloadCooldownSeconds() + "s");
        }

        // ── 7. Update GUI views ──────────────────────────────────────────
        plugin.getMachineManager().updateSessionsFor(data);

        // ── 8. Persist heat async ─────────────────────────────────────────
        persistHeatAsync();
    }

    // ────────────────────────────────────────────────────────────────────────

    private void persistHeatAsync() {
        Location loc = data.getLocation();
        if (loc.getWorld() == null) return;
        double heat     = data.getHeat();
        long   overload = data.getOverloadUntilMs();
        plugin.getServer().getAsyncScheduler().runNow(plugin, t ->
                db.updateMachineHeat(
                        loc.getWorld().getName(),
                        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                        heat, overload)
        );
    }
}
