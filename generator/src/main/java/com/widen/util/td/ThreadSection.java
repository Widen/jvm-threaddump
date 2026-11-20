package com.widen.util.td;

import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.widen.util.td.Line.BlankLine;
import static com.widen.util.td.Line.SimpleLine;
import static com.widen.util.td.Line.TitledLine;
import static java.lang.String.format;

/**
 * Every thread in the snapshot with its state, lock, and stack.
 */
final class ThreadSection
{
    private final JvmSnapshot snapshot;

    ThreadSection(JvmSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    List<Line> lines() {
        List<Line> lines = new ArrayList<>();
        lines.add(new TitledLine("Thread Count", String.valueOf(snapshot.threads.size())));
        lines.add(new BlankLine());
        for (ThreadSnapshot thread : snapshot.threads) {
            lines.add(new SimpleLine(formatHeader(thread)));
            for (StackTraceElement frame : thread.stack()) {
                lines.add(new SimpleLine(formatFrame(frame)));
            }
            lines.add(new BlankLine());
        }
        return lines;
    }

    private static String formatHeader(ThreadSnapshot snapshot) {
        Thread thread = snapshot.thread;
        ThreadInfo info = snapshot.info;
        String name = snapshot.name;
        ThreadGroup group = thread.getThreadGroup();
        StringBuilder header = new StringBuilder(format(Locale.US, "\"%s\"%s priority=%d id=0x%x group=%s cpu=%s block_cnt=%d wait_cnt=%d %s",
                name, thread.isDaemon() ? " daemon" : "", thread.getPriority(), thread.getId(),
                group == null ? "-DEAD-" : group.getName(), formatCpuTime(snapshot.cpuNanos), info.getBlockedCount(),
                info.getWaitedCount(), info.getThreadState()));

        long lockOwnerId = info.getLockOwnerId();
        String lockName = info.getLockName();
        if (lockOwnerId != -1 && lockName != null) {
            header.append(format(Locale.US, " (waiting on 0x%x for %s)", lockOwnerId, lockName));
        } else if (lockOwnerId != -1) {
            header.append(format(Locale.US, " (waiting on 0x%x)", lockOwnerId));
        } else if (lockName != null) {
            header.append(format(" (waiting for %s)", lockName));
        }

        // skip the standard Thread.toString() and a toString() that repeats the name
        String threadToString = snapshot.toString;
        if (threadToString != null && !threadToString.startsWith("Thread[") && !threadToString.equalsIgnoreCase(name)) {
            header.append(format(" (%s)", threadToString));
        }
        return header.toString();
    }

    /**
     * Formats CPU time as milliseconds below ten seconds and as whole seconds above, or {@code -1} when the time is
     * not available.
     */
    static String formatCpuTime(long nanos) {
        if (nanos == -1) {
            return "-1";
        }
        long millis = nanos / 1_000_000;
        return millis < 10_000 ? millis + "ms" : millis / 1000 + "s";
    }

    static String formatFrame(StackTraceElement frame) {
        StringBuilder sb = new StringBuilder("    " + frame.getClassName() + "." + frame.getMethodName());
        String fileName = frame.getFileName();
        if (fileName != null) {
            sb.append("(").append(fileName);
            if (frame.getLineNumber() > 0) {
                sb.append(":").append(frame.getLineNumber());
            }
            sb.append(")");
        } else if (frame.isNativeMethod()) {
            sb.append("(Native Method)");
        } else {
            sb.append("(Unknown Source)");
        }
        return sb.toString();
    }
}
