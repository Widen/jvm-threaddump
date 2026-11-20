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
    private final JvmSnapshot snapshot;

    DeadlockSection(JvmSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    List<Line> lines() {
        String value = snapshot.deadlockedIds.length == 0
                ? "None"
                : Arrays.stream(snapshot.deadlockedIds).mapToObj(id -> "0x" + Long.toHexString(id)).collect(Collectors.joining(", "));
        return Collections.singletonList(new TitledLine("Deadlocked Threads", value));
    }
}
