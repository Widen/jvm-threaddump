package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Flags threads that kept a core busy during the sampling window, and shows where each one is now.
 */
final class HotThreadsCheck implements Check
{
    static final String TITLE = "Hot Threads";
    static final int MIN_PERCENT_OF_CORE = 50;
    static final int MAX_THREADS = 5;
    static final int MAX_FRAMES = 3;

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return snapshot.sampled() && snapshot.cpuTimeAvailable;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        List<ThreadSnapshot> hot = new ArrayList<>();
        for (ThreadSnapshot thread : snapshot.threads) {
            if (thread.windowCpuNanos >= 0
                    && thread.windowCpuNanos * 100 >= MIN_PERCENT_OF_CORE * snapshot.windowNanos) {
                hot.add(thread);
            }
        }
        if (hot.isEmpty()) {
            return Collections.emptyList();
        }
        hot.sort(Comparator.comparingLong((ThreadSnapshot thread) -> thread.windowCpuNanos).reversed());

        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.US, "%d %s used %d%% or more of one core over the last %.1fs", hot.size(),
                hot.size() == 1 ? "thread" : "threads", MIN_PERCENT_OF_CORE, snapshot.windowNanos / 1_000_000_000d));
        List<String> marks = new ArrayList<>();
        for (ThreadSnapshot thread : hot.subList(0, Math.min(hot.size(), MAX_THREADS))) {
            lines.add(String.format(Locale.US, "%s %d%% of one core", thread.label(),
                    thread.windowCpuNanos * 100 / snapshot.windowNanos));
            List<StackTraceElement> frames = StackFilter.kept(thread.stack());
            for (StackTraceElement frame : frames.subList(0, Math.min(frames.size(), MAX_FRAMES))) {
                lines.add(ThreadSection.formatFrame(frame));
            }
            marks.add(Finding.threadMark(thread.id()));
        }
        return Collections.singletonList(new Finding(TITLE, lines, marks));
    }
}
