package com.kcrp.kcrypto.machines;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MachineData – in-memory state for a single K-Miner machine.
 *
 * <p>Fields that are mutated by the ticking task use {@link AtomicReference}
 * or primitive volatile fields so reads from other threads (e.g. GUI opens)
 * are always consistent without requiring external locks.</p>
 */
public final class MachineData {

    /** Maximum heat before overload triggers. */
    public static final double MAX_HEAT = 100.0;

    private final UUID     ownerUuid;
    private final Location location;

    // Heat is written by the RegionScheduler tick and read by GUI/admin threads
    private volatile double heat;
    private volatile long   overloadUntilMs;   // epoch-ms; 0 = not in overload

    public enum NotifMode {
        ALL("§aToutes les minerais"), 
        FINISHED("§eÀ la fin du stock"), 
        OFF("§cDésactivé");
        
        private final String displayName;
        NotifMode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
        public NotifMode next() {
            int nextOrdinal = (this.ordinal() + 1) % values().length;
            return values()[nextOrdinal];
        }
    }
    private volatile NotifMode notifMode = NotifMode.ALL;

    /**
     * Virtual ore inventory: contents placed by the player.
     * Accessed only from the region thread that owns this chunk, so
     * synchronisation is handled by Folia's region guarantees.
     */
    private final ItemStack[] oreSlots;

    /** Reference to the active Folia ticking task, so it can be cancelled. */
    private final AtomicReference<Object> tickTaskRef = new AtomicReference<>(null);

    /**
     * Skip counter for dynamic mining difficulty.
     *
     * <p>Set by {@link MachineTickTask#refreshDifficulty} based on the formula:
     * <pre>
     *   TimeMultiplier = 1.0 + (C / 1000.0)
     *   skips = floor(C / 1000)   (how many extra ticks to skip per cycle)
     * </pre>
     *
     * <p>Written from async thread (MachineManager rate refresh) and read from
     * region thread (tick task). AtomicInteger provides the necessary visibility.
     */
    private final AtomicInteger skipCounter = new AtomicInteger(0);
    /** Base skip value for the current difficulty level (used to reset after each cycle). */
    private volatile int skipBase = 0;

    /** Number of virtual GUI slots reserved for ore insertion. */
    public static final int ORE_SLOT_COUNT = 27;

    public MachineData(UUID ownerUuid, Location location,
                       double heat, long overloadUntilMs, NotifMode notifMode) {
        this.ownerUuid       = ownerUuid;
        this.location        = location.clone();
        this.heat            = heat;
        this.overloadUntilMs = overloadUntilMs;
        this.notifMode       = notifMode != null ? notifMode : NotifMode.ALL;
        this.oreSlots        = new ItemStack[ORE_SLOT_COUNT];
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Accessors
    // ────────────────────────────────────────────────────────────────────────

    public UUID     getOwnerUuid()       { return ownerUuid;       }
    public Location getLocation()        { return location.clone(); }
    public double   getHeat()            { return heat;             }
    public long     getOverloadUntilMs() { return overloadUntilMs; }
    public ItemStack[] getOreSlots()     { return oreSlots;         }
    public NotifMode getNotifMode()      { return notifMode;        }
    public void setNotifMode(NotifMode mode) { this.notifMode = mode; }

    /** Sets the heat level, clamped to [0, MAX_HEAT]. */
    public void setHeat(double heat) {
        this.heat = Math.max(0.0, Math.min(MAX_HEAT, heat));
    }

    public void setOverloadUntilMs(long overloadUntilMs) {
        this.overloadUntilMs = overloadUntilMs;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  State queries
    // ────────────────────────────────────────────────────────────────────────

    /** Returns true if the machine is currently in overload (cooling down). */
    public boolean isInOverload() {
        return overloadUntilMs > 0 && System.currentTimeMillis() < overloadUntilMs;
    }

    /** Returns true if there is at least one ore present in the virtual slots. */
    public boolean hasOres() {
        for (ItemStack slot : oreSlots) {
            if (slot != null && slot.getAmount() > 0) return true;
        }
        return false;
    }

    /**
     * Removes exactly one unit from the first non-empty ore slot and returns
     * the single-unit ItemStack of that ore type, or {@code null} if empty.
     */
    public ItemStack consumeOneOre() {
        for (int i = 0; i < oreSlots.length; i++) {
            ItemStack slot = oreSlots[i];
            if (slot != null && slot.getAmount() > 0) {
                ItemStack single = slot.clone();
                single.setAmount(1);
                slot.setAmount(slot.getAmount() - 1);
                if (slot.getAmount() <= 0) {
                    oreSlots[i] = null;
                }
                return single;
            }
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Ticking task handle
    // ────────────────────────────────────────────────────────────────────────

    /** Stores the Folia task object (opaque reference). */
    public void setTickTask(Object task) {
        tickTaskRef.set(task);
    }

    /** Retrieves the stored Folia task object. */
    public Object getTickTask() {
        return tickTaskRef.get();
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Dynamic difficulty skip counter
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Sets the difficulty skip base. Called from async context by MachineManager.
     * @param skips number of extra ticks to skip per ore processing cycle (≥ 0)
     */
    public void setSkipBase(int skips) {
        this.skipBase = Math.max(0, skips);
        this.skipCounter.set(Math.max(0, skips));
    }

    /**
     * Decrements the skip counter and returns the new value.
     * Called from region thread in MachineTickTask.run().
     * @return current counter value after decrement
     */
    public int decrementAndGetSkip() {
        return skipCounter.decrementAndGet();
    }

    /**
     * Resets the skip counter to its base value after a full processing cycle.
     * Called from region thread in MachineTickTask.run().
     */
    public void resetSkip() {
        skipCounter.set(skipBase);
    }

    // ── Rarity Skips ────────────────────────────────────────────────────────

    private int raritySkipCounter = 0;

    public int decrementAndGetRaritySkip() {
        if (raritySkipCounter > 0) {
            raritySkipCounter--;
            return raritySkipCounter;
        }
        return 0;
    }

    public void setRaritySkip(int skips) {
        this.raritySkipCounter = Math.max(0, skips);
    }

    /**
     * Peeks at the first available ore without consuming it.
     */
    public ItemStack peekFirstOre() {
        for (int i = 0; i < oreSlots.length; i++) {
            ItemStack slot = oreSlots[i];
            if (slot != null && slot.getAmount() > 0) {
                return slot;
            }
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Location key helper
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Returns a stable string key for this machine's location,
     * used as a map key in {@link MachineManager}.
     */
    public String getLocationKey() {
        return toKey(location);
    }

    public static String toKey(Location loc) {
        if (loc.getWorld() == null) return "null:" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
