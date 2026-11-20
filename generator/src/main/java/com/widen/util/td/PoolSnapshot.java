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
}
