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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * MachineTickTask – the per-machine Runnable executed by Folia's
 * {@code RegionScheduler} at a fixed interval.
 *
 * <h3>Tick logic</h3>
 * <ol>
 *   <li>If machine is in overload: check if cooldown has expired; if not, return early.</li>
 *   <li>Apply dynamic difficulty multiplier (skip ticks based on circulation).</li>
 *   <li>Consume one ore unit from the virtual inventory.</li>
 *   <li>Award K-Crypto to the owner's wallet (async DB write).</li>
 *   <li>Add heat. If heat ≥ 100 → trigger overload: smoke particles, hiss sound,
 *       set overload timestamp, persist to DB.</li>
 *   <li>Persist heat to DB every tick (async).</li>
 * </ol>
 *
 * <h3>Dynamic Mining Difficulty Formula</h3>
 * <pre>
 *   C              = total K-Crypto in circulation
 *   TimeMultiplier = 1.0 + (C / 1000.0)
 * </pre>
 *
 * <p>The multiplier is implemented as a tick-skip counter: if the multiplier is 2.0,
 * the task skips every other invocation, effectively doubling processing time
 * without cancelling/rescheduling the underlying Folia task.
 *
 * <p>Example: C = 1000 → multiplier = 2.0 → machines take 2× longer per ore.
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

    /**
     * Counts how many additional ticks to skip due to the difficulty multiplier.
     * Written from async (refreshed by MachineManager) and read on region thread;
     * AtomicInteger ensures visibility without locks.
     */
    private final AtomicInteger skipCounter = new AtomicInteger(0);

    public MachineTickTask(KcryptoPlugin plugin, MachineData data,
                           DatabaseManager db, EconomyManager economy,
                           ConfigManager cfg) {
        this.plugin  = plugin;
        this.data    = data;
        this.db      = db;
        this.economy = economy;
        this.cfg     = cfg;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Dynamic difficulty refresh
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Called asynchronously by {@link com.kcrp.kcrypto.machines.MachineManager}
     * after each rate update to refresh the tick-skip counter.
     *
     * <h3>Formula</h3>
     * <pre>
     *   TimeMultiplier = 1.0 + (C / 1000.0)
     * </pre>
     *
     * <p>A multiplier of 1.0 means no skipping (base speed).
     * A multiplier of 2.0 means skip 1 tick for every real tick (half speed).
     * The skipCounter is set to {@code floor(multiplier) - 1} additional ticks
     * to wait per cycle.
     *
     * @param totalCirculation total K-Crypto in circulation (C)
     */
    public void refreshDifficulty(double totalCirculation) {
        // TimeMultiplier = 1.0 + (C / 1000.0)
        // C=0    → multiplier=1.0 (no skip)
        // C=1000 → multiplier=2.0 (skip 1 tick = 2× slower)
        // C=5000 → multiplier=6.0 (skip 5 ticks = 6× slower)
        int skips = (int) Math.floor(totalCirculation / 1000.0);
        skipCounter.set(Math.max(0, skips));
    }

    @Override
    public void run() {
        Location loc = data.getLocation();

        // ── 0. Dynamic difficulty: skip ticks based on circulation ────────
        // TimeMultiplier = 1.0 + (C / 1000.0) — implemented as a skip counter.
        // skipCounter stores "how many extra ticks to skip per processing cycle".
        // Decrement each call; only process when we reach 0, then reset.
        if (data.decrementAndGetSkip() > 0) {
            return; // skipping this invocation to simulate slower processing
        }
        data.resetSkip(); // reset for next cycle

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

        // ── 2. No ores? Passive cooling ──────────────────────────────────────
        if (!data.hasOres()) {
            double currentHeat = data.getHeat();
            if (currentHeat > 0) {
                // Machine cools down passively when idle
                double coolingRate = cfg.getPassiveCoolingRate();
                data.setHeat(Math.max(0.0, currentHeat - coolingRate));
                persistHeatAsync();
                // Update GUI views to show cooling
                plugin.getMachineManager().updateSessionsFor(data);
            }
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
