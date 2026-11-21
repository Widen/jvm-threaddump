package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Flags a container that the kernel often stops because its threads used the CPU quota before the period ended,
 * measured over the sampling window and over the container's lifetime once the JVM has run for five minutes, because
 * startup work throttles a younger JVM. The window measure needs at least two throttled periods, so one burst in a
 * nearly idle container does not flag.
 */
final class CpuThrottledCheck implements Check
{
    static final String TITLE = "CPU Throttled";
    static final int MIN_WINDOW_PERCENT = 25;
    static final long MIN_WINDOW_THROTTLED_PERIODS = 2;
    static final int MIN_LIFETIME_PERCENT = 10;
    static final long MIN_LIFETIME_UPTIME_MILLIS = 300_000;

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return snapshot.cpuLimit >= 0;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        long windowThrottled = snapshot.windowCpuThrottledPeriods;
        long windowPeriods = snapshot.windowCpuPeriods;
        if (snapshot.sampled() && windowPeriods > 0 && windowThrottled >= MIN_WINDOW_THROTTLED_PERIODS
                && windowThrottled * 100 >= MIN_WINDOW_PERCENT * windowPeriods) {
            lines.add(String.format(Locale.US,
                    "the kernel throttled this container in %d of %d periods (%d%%) of the last %.1fs",
                    windowThrottled, windowPeriods, windowThrottled * 100 / windowPeriods,
                    snapshot.windowNanos / 1_000_000_000d));
        }

        long throttled = snapshot.cpuThrottledPeriods;
        long periods = snapshot.cpuPeriods;
        if (snapshot.uptimeMillis >= MIN_LIFETIME_UPTIME_MILLIS && periods > 0
                && throttled * 100 >= MIN_LIFETIME_PERCENT * periods) {
            lines.add(String.format(Locale.US,
                    "the kernel has throttled this container in %d%% of its periods, an average since it started",
                    throttled * 100 / periods));
        }

        if (lines.isEmpty()) {
            return Collections.emptyList();
        }
        lines.add(String.format(Locale.US, "the limit is %.2f CPUs", snapshot.cpuLimit));
        List<String> marks = Collections.singletonList(Finding.lineMark(JvmInfoSection.CPU_LIMIT));
        return Collections.singletonList(new Finding(TITLE, lines, marks));
    }
}
