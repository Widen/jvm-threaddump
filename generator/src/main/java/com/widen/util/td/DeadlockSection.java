package com.widen.util.td;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.widen.util.td.Line.TitledLine;

/**
 * The ids of the threads the JVM reports as deadlocked.
 */
final class DeadlockSection
{
    private final JvmSources jvm;

    DeadlockSection(JvmSources jvm) {
        this.jvm = jvm;
    }

    List<Line> lines() {
        long[] deadlockedThreads = jvm.threads().findDeadlockedThreads();
        String value = deadlockedThreads == null
                ? "None"
                : Arrays.stream(deadlockedThreads).mapToObj(id -> "0x" + Long.toHexString(id)).collect(Collectors.joining(", "));
        return Collections.singletonList(new TitledLine("Deadlocked Threads", value));
    }
}
