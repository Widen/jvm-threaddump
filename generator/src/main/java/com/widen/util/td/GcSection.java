package com.widen.util.td;

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
    private final JvmSnapshot snapshot;

    GcSection(JvmSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    List<Line> lines() {
        List<Line> lines = new ArrayList<>();
        for (GcSnapshot gc : snapshot.collectors) {
            lines.add(new TitledLine("GC " + gc.name, format(Locale.US, "spent %s doing %d collections",
                    Durations.hoursMinutesSecondsMillis(gc.timeMillis), gc.count)));
        }
        lines.add(new BlankLine());
        return lines;
    }
}
