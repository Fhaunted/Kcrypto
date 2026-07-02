package com.kcrp.kcrypto.machines;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
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
