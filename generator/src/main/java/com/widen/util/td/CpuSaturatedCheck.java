package com.widen.util.td;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Flags a host whose CPU is nearly all in use. In a container with a CPU limit, the JVM reports use of that limit, so
 * the load measures how close the container is to being throttled.
 */
final class CpuSaturatedCheck implements Check
{
    static final String TITLE = "CPU Saturated";
    static final double MIN_SYSTEM_LOAD = 0.90;

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return snapshot.systemCpuLoad >= 0 && snapshot.processCpuLoad >= 0;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        if (snapshot.systemCpuLoad < MIN_SYSTEM_LOAD) {
            return Collections.emptyList();
        }
        String line = String.format(Locale.US, "system CPU load is %.2f; this JVM uses %.2f (0.0 to 1.0)",
                snapshot.systemCpuLoad, snapshot.processCpuLoad);
        return Collections.singletonList(
                new Finding(TITLE, Collections.singletonList(line), Collections.singletonList(Finding.lineMark("CPU Load"))));
    }
}
