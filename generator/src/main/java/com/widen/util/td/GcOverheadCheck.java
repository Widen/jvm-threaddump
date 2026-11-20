package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Flags a JVM that spends much of its time collecting garbage, measured over the sampling window and over the JVM's
 * lifetime. Collectors that report concurrent work instead of pauses are left out of both measures.
 */
final class GcOverheadCheck implements Check
{
    static final String TITLE = "GC Overhead";
    static final int MIN_WINDOW_PERCENT = 25;
    static final long MIN_WINDOW_GC_MILLIS = 500;
    static final int MIN_LIFETIME_PERCENT = 10;

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return true;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        List<GcSnapshot> counted = new ArrayList<>();
        for (GcSnapshot gc : snapshot.collectors) {
            if (!gc.name.contains("Cycles") && !gc.name.contains("Concurrent")) {
                counted.add(gc);
            }
        }

        List<String> lines = new ArrayList<>();
        Set<String> marks = new LinkedHashSet<>();
        boolean windowFlagged = false;
        if (snapshot.sampled()) {
            long windowMillis = 0;
            long windowCount = 0;
            for (GcSnapshot gc : counted) {
                windowMillis += gc.windowTimeMillis;
                windowCount += gc.windowCount;
            }
            long windowNanos = snapshot.windowNanos;
            windowFlagged = windowMillis >= MIN_WINDOW_GC_MILLIS
                    && windowMillis * 100_000_000L >= MIN_WINDOW_PERCENT * windowNanos;
            if (windowFlagged) {
                lines.add(String.format(Locale.US, "GC took %d%% of the last %.1fs in %d %s",
                        windowMillis * 100_000_000L / windowNanos, windowNanos / 1_000_000_000d, windowCount,
                        windowCount == 1 ? "collection" : "collections"));
                lines.addAll(Allocators.lines(snapshot));
                for (GcSnapshot gc : counted) {
                    if (gc.windowCount > 0) {
                        marks.add(Finding.gcMark(gc.name));
                    }
                }
            }
        }

        long lifetimeMillis = 0;
        for (GcSnapshot gc : counted) {
            lifetimeMillis += Math.max(0, gc.timeMillis);
        }
        long uptimeMillis = snapshot.uptimeMillis;
        if (uptimeMillis > 0 && lifetimeMillis * 100 >= MIN_LIFETIME_PERCENT * uptimeMillis) {
            lines.add(String.format(Locale.US, "GC has taken %d%% of JVM uptime, an average since the JVM started",
                    lifetimeMillis * 100 / uptimeMillis));
            if (!windowFlagged) {
                for (GcSnapshot gc : counted) {
                    marks.add(Finding.gcMark(gc.name));
                }
            }
        }

        if (lines.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new Finding(TITLE, lines, marks));
    }
}
