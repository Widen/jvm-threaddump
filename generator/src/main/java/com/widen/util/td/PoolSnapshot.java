package com.widen.util.td;

import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

/**
 * A memory pool that reports usage, read once.
 */
final class PoolSnapshot
{
    final String name;
    final MemoryType type;
    final MemoryUsage usage;
    /** Usage after the last collection, or null when the pool reports none. */
    final MemoryUsage collectionUsage;

    PoolSnapshot(String name, MemoryType type, MemoryUsage usage, MemoryUsage collectionUsage) {
        this.name = name;
        this.type = type;
        this.usage = usage;
        this.collectionUsage = collectionUsage;
    }

    /** The share of the max in use now, in whole percent rounded down, or -1 when the max is undefined. */
    long usedPercent() {
        return percentOfMax(usage.getUsed());
    }

    /**
     * The share of the max in use after the last collection, in whole percent rounded down, or -1 when the max is
     * undefined or the pool reports no reading, or zero bytes used.
     */
    long collectionUsedPercent() {
        if (collectionUsage == null || collectionUsage.getUsed() <= 0) {
            return -1;
        }
        return percentOfMax(collectionUsage.getUsed());
    }

    private long percentOfMax(long usedBytes) {
        long max = usage.getMax();
        return max > 0 ? usedBytes * 100 / max : -1;
    }
}
