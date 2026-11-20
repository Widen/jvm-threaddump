package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flags threads that wait on each other for locks and will never proceed. The JVM's report is only the trigger: on
 * Java 8 it can include a thread that waits behind the deadlock, so the check finds the cycles in the owner graph
 * itself.
 */
final class DeadlockCheck implements Check
{
    static final String TITLE = "Deadlock";

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return true;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        if (snapshot.deadlockedIds.length == 0) {
            return Collections.emptyList();
        }
        LockGraph locks = snapshot.locks();
        if (locks.cycles().isEmpty()) {
            return Collections.singletonList(jvmReport(snapshot));
        }
        List<Finding> findings = new ArrayList<>();
        for (List<ThreadSnapshot> cycle : locks.cycles()) {
            findings.add(cycleFinding(cycle, locks.behind(cycle).size()));
        }
        return findings;
    }

    private static Finding cycleFinding(List<ThreadSnapshot> cycle, int behindCount) {
        List<String> lines = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        lines.add(cycle.size() + " threads block each other and will never proceed");
        for (int i = 0; i < cycle.size(); i++) {
            ThreadSnapshot thread = cycle.get(i);
            ThreadSnapshot holder = cycle.get((i + 1) % cycle.size());
            String lockName = thread.info.getLockName();
            lines.add(thread.label() + " waits for " + (lockName == null ? "a lock" : lockName) + ", held by \""
                    + holder.name + "\"");
            StackTraceElement[] stack = thread.stack();
            if (stack.length > 0) {
                lines.add(ThreadSection.formatFrame(stack[0]));
            }
            marks.add(Finding.threadMark(thread.id()));
        }
        if (behindCount > 0) {
            lines.add(behindCount + (behindCount == 1 ? " other thread waits" : " other threads wait") + " behind them");
        }
        return new Finding(TITLE, lines, marks);
    }

    private static Finding jvmReport(JvmSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        lines.add(snapshot.deadlockedIds.length + " threads are deadlocked, by the JVM's report");
        for (long id : snapshot.deadlockedIds) {
            ThreadSnapshot thread = snapshot.thread(id);
            if (thread == null) {
                lines.add("thread 0x" + Long.toHexString(id));
            } else {
                lines.add(thread.label());
                marks.add(Finding.threadMark(id));
            }
        }
        return new Finding(TITLE, lines, marks);
    }
}
