package com.widen.util.td;

import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.widen.util.td.Line.BlankLine;
import static com.widen.util.td.Line.SimpleLine;
import static com.widen.util.td.Line.TitledLine;
import static java.lang.String.format;

/**
 * Every live thread with its state, lock, and stack. A thread prints only when it is in both the live thread set and
 * the {@link ThreadMXBean} snapshot, so each header and stack come from one consistent moment.
 */
final class ThreadSection
{
    private static final Comparator<ThreadSnapshot> ORDER = Comparator
            .comparing((ThreadSnapshot snapshot) -> snapshot.name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(snapshot -> snapshot.name)
            .thenComparingLong(snapshot -> snapshot.thread.getId());

    private final JvmSources jvm;

    ThreadSection(JvmSources jvm) {
        this.jvm = jvm;
    }

    List<Line> lines() {
        ThreadMXBean threadBean = jvm.threads();
        boolean cpuTimeEnabled = threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled();

        Map<Long, ThreadInfo> infos = new HashMap<>();
        for (ThreadInfo info : threadBean.dumpAllThreads(false, false)) {
            infos.put(info.getThreadId(), info);
        }
        List<ThreadSnapshot> threads = new ArrayList<>();
        for (Thread thread : jvm.liveThreads()) {
            ThreadInfo info = infos.get(thread.getId());
            if (info != null) {
                threads.add(new ThreadSnapshot(thread, info, thread.getName()));
            }
        }
        threads.sort(ORDER);

        List<Line> lines = new ArrayList<>();
        lines.add(new TitledLine("Thread Count", String.valueOf(threads.size())));
        lines.add(new BlankLine());
        for (ThreadSnapshot snapshot : threads) {
            Thread thread = snapshot.thread;
            ThreadInfo info = snapshot.info;
            long cpuTimeNanos = cpuTimeEnabled ? threadBean.getThreadCpuTime(thread.getId()) : -1;
            lines.add(new SimpleLine(formatHeader(snapshot, cpuTimeNanos)));
            for (StackTraceElement frame : info.getStackTrace()) {
                lines.add(new SimpleLine(formatFrame(frame)));
            }
            lines.add(new BlankLine());
        }
        return lines;
    }

    private static String formatHeader(ThreadSnapshot snapshot, long cpuTimeNanos) {
        Thread thread = snapshot.thread;
        ThreadInfo info = snapshot.info;
        String name = snapshot.name;
        ThreadGroup group = thread.getThreadGroup();
        StringBuilder header = new StringBuilder(format("\"%s\"%s priority=%d id=0x%x group=%s cpu=%s block_cnt=%d wait_cnt=%d %s",
                name, thread.isDaemon() ? " daemon" : "", thread.getPriority(), thread.getId(),
                group == null ? "-DEAD-" : group.getName(), formatCpuTime(cpuTimeNanos), info.getBlockedCount(),
                info.getWaitedCount(), info.getThreadState()));

        long lockOwnerId = info.getLockOwnerId();
        String lockName = info.getLockName();
        if (lockOwnerId != -1 && lockName != null) {
            header.append(format(" (waiting on 0x%x for %s)", lockOwnerId, lockName));
        } else if (lockOwnerId != -1) {
            header.append(format(" (waiting on 0x%x)", lockOwnerId));
        } else if (lockName != null) {
            header.append(format(" (waiting for %s)", lockName));
        }

        // skip the standard Thread.toString() and a toString() that repeats the name
        String threadToString = thread.toString();
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

    /**
     * A thread with the name read once, so a thread that renames itself cannot change its sort position or header
     * mid-dump.
     */
    private static final class ThreadSnapshot
    {
        private final Thread thread;
        private final ThreadInfo info;
        private final String name;

        ThreadSnapshot(Thread thread, ThreadInfo info, String name) {
            this.thread = thread;
            this.info = info;
            this.name = name;
        }
    }
}
