package com.widen.util.td;

/**
 * A garbage collector's totals, read once.
 */
final class GcSnapshot
{
    final String name;
    final long count;
    /** Total collection time, or -1 when undefined. */
    final long timeMillis;
    /** Collections inside the sampling window, or 0 when not sampled. */
    final long windowCount;
    /** Collection time inside the sampling window, or 0 when not sampled. */
    final long windowTimeMillis;

    GcSnapshot(String name, long count, long timeMillis, long windowCount, long windowTimeMillis) {
        this.name = name;
        this.count = count;
        this.timeMillis = timeMillis;
        this.windowCount = windowCount;
        this.windowTimeMillis = windowTimeMillis;
    }
}
