package com.widen.util.td;

import java.lang.management.GarbageCollectorMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.widen.util.td.Line.BlankLine;
import static com.widen.util.td.Line.TitledLine;
import static java.lang.String.format;

/**
 * The time each garbage collector has spent collecting.
 */
final class GcSection
{
    private final JvmSources jvm;

    GcSection(JvmSources jvm) {
        this.jvm = jvm;
    }

    List<Line> lines() {
        List<Line> lines = new ArrayList<>();
        for (GarbageCollectorMXBean gc : jvm.garbageCollectors()) {
            lines.add(new TitledLine("GC " + gc.getName(), format(Locale.US, "spent %s doing %d collections",
                    Durations.hoursMinutesSecondsMillis(gc.getCollectionTime()), gc.getCollectionCount())));
        }
        lines.add(new BlankLine());
        return lines;
    }
}
