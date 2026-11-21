package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.widen.util.td.Line.TitledLine;

/**
 * The threads in a lock cycle, or the threads the JVM reports as deadlocked when the owner graph holds no cycle.
 */
final class DeadlockSection
{
    private final JvmSnapshot snapshot;

    DeadlockSection(JvmSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    List<Line> lines() {
        List<Long> ids = snapshot.locks().deadlockedIds();
        List<String> labels = new ArrayList<>();
        if (ids.isEmpty()) {
            labels.add("None");
        }
        for (long id : ids) {
            ThreadSnapshot thread = snapshot.thread(id);
            labels.add(thread == null ? "0x" + Long.toHexString(id) : thread.label());
        }
        return Collections.singletonList(new TitledLine("Deadlocked Threads", String.join(", ", labels)));
    }
}
