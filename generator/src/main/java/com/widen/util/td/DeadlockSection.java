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
        List<String> labels = new ArrayList<>();
        if (snapshot.deadlockedIds.length == 0) {
            labels.add("None");
        } else if (!snapshot.locks().cycles().isEmpty()) {
            for (List<ThreadSnapshot> cycle : snapshot.locks().cycles()) {
                for (ThreadSnapshot thread : cycle) {
                    labels.add(thread.label());
                }
            }
        } else {
            for (long id : snapshot.deadlockedIds) {
                ThreadSnapshot thread = snapshot.thread(id);
                labels.add(thread == null ? "0x" + Long.toHexString(id) : thread.label());
            }
        }
        return Collections.singletonList(new TitledLine("Deadlocked Threads", String.join(", ", labels)));
    }
}
