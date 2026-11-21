package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Flags a container that the kernel often stops because its threads used the CPU quota before the period ended,
 * measured over the sampling window and over the JVM's uptime once the JVM has run for five minutes, because startup
 * work throttles a younger JVM. The window measure needs at least two throttled periods.
 * <p>
 * The kernel counts a period only when the container had work to run in it, so a ratio of its counters would flag a
 * mostly idle container that was throttled in its few busy periods. Both measures therefore count each throttled
 * period as a whole period of elapsed time and compare that with the window or the uptime. The counters cover the
 * container's life, so a JVM that started in an older container also counts the throttling before it.
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
        long windowThrottledNanos = windowThrottled * snapshot.cpuPeriodMicros * 1000;
        long windowNanos = snapshot.windowNanos;
        if (snapshot.sampled() && windowThrottled >= MIN_WINDOW_THROTTLED_PERIODS
                && windowThrottledNanos * 100 >= MIN_WINDOW_PERCENT * windowNanos) {
            lines.add(String.format(Locale.US, "the kernel throttled this container in %d periods (%d%%) of the last %.1fs",
                    windowThrottled, windowThrottledNanos * 100 / windowNanos, windowNanos / 1_000_000_000d));
        }

        long throttledMicros = snapshot.cpuThrottledPeriods * snapshot.cpuPeriodMicros;
        long uptimeMillis = snapshot.uptimeMillis;
        if (uptimeMillis >= MIN_LIFETIME_UPTIME_MILLIS
                && throttledMicros * 100 >= MIN_LIFETIME_PERCENT * uptimeMillis * 1000) {
            lines.add(String.format(Locale.US, "the kernel has throttled this container for %d%% of its uptime",
                    throttledMicros / 10 / uptimeMillis));
        }

        if (lines.isEmpty()) {
            return Collections.emptyList();
        }
        lines.add(String.format(Locale.US, "the limit is %.2f CPUs", snapshot.cpuLimit));
        List<String> marks = Collections.singletonList(Finding.lineMark(JvmInfoSection.CPU_LIMIT));
        return Collections.singletonList(new Finding(TITLE, lines, marks));
    }
}
