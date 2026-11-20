package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The lines that name the threads allocating the most during the sampling window.
 */
final class Allocators
{
    static final long MIN_BYTES_PER_SECOND = 1024L * 1024L;
    private static final int LISTED = 3;
    private static final String PREFIX = "Top allocators: ";
    private static final String INDENT = String.format("%" + PREFIX.length() + "s", "");

    private Allocators() {
    }

    /**
     * Returns the top allocators with their megabytes per second, or an empty list when the snapshot is not sampled,
     * allocation is unavailable, or no thread reached {@link #MIN_BYTES_PER_SECOND}.
     */
    static List<String> lines(JvmSnapshot snapshot) {
        if (!snapshot.sampled() || !snapshot.allocationAvailable) {
            return Collections.emptyList();
        }
        double seconds = snapshot.windowNanos / 1_000_000_000d;
        List<ThreadSnapshot> allocators = new ArrayList<>();
        for (ThreadSnapshot thread : snapshot.threads) {
            if (thread.windowAllocatedBytes / seconds >= MIN_BYTES_PER_SECOND) {
                allocators.add(thread);
            }
        }
        allocators.sort((one, other) -> Long.compare(other.windowAllocatedBytes, one.windowAllocatedBytes));

        List<String> lines = new ArrayList<>();
        for (ThreadSnapshot thread : allocators.subList(0, Math.min(LISTED, allocators.size()))) {
            long megabytesPerSecond = Math.round(thread.windowAllocatedBytes / seconds / (1024L * 1024L));
            lines.add((lines.isEmpty() ? PREFIX : INDENT)
                    + String.format(Locale.US, "%s %,d MB/s", thread.label(), megabytesPerSecond));
        }
        return lines;
    }
}
