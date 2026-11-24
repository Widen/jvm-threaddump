package com.widen.util.td;

import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private final Findings findings;
    private final boolean allFrames;
    private final ThreadGrouping grouping;

    ThreadSection(JvmSnapshot snapshot, Findings findings, boolean allFrames, ThreadGrouping grouping) {
        this.snapshot = snapshot;
        this.findings = findings;
        this.allFrames = allFrames;
        this.grouping = grouping;
    }

    List<Line> lines() {
        List<Line> lines = new ArrayList<>();
        String count = String.valueOf(snapshot.threads.size());
        if (!snapshot.threads.isEmpty()) {
            count += " (" + formatStateCounts(snapshot.threads) + ")";
        }
        lines.add(new TitledLine("Thread Count", count));
        lines.add(new BlankLine());
        for (List<ThreadSnapshot> group : groups()) {
            for (ThreadSnapshot thread : group) {
                lines.add(new SimpleLine(formatHeader(thread) + findings.marker(Finding.threadMark(thread.id()))));
            }
            StackTraceElement[] stack = group.get(0).stack();
            List<StackTraceElement> shown = allFrames ? Arrays.asList(stack) : StackFilter.kept(stack);
            for (StackTraceElement frame : shown) {
                lines.add(new SimpleLine(formatFrame(frame)));
            }
            int hiddenCount = stack.length - shown.size();
            if (hiddenCount > 0) {
                lines.add(new SimpleLine("    ... " + hiddenCount + (hiddenCount == 1 ? " frame" : " frames")
                        + " hidden"));
            }
            lines.add(new BlankLine());
        }
        return lines;
    }

    /**
     * Splits the threads, in name order, into the groups that share one printed stack.
     */
    private List<List<ThreadSnapshot>> groups() {
        List<List<ThreadSnapshot>> groups = new ArrayList<>();
        Map<List<StackTraceElement>, List<ThreadSnapshot>> groupsByStack = new HashMap<>();
        for (ThreadSnapshot thread : snapshot.threads) {
            List<StackTraceElement> stack = Arrays.asList(thread.stack());
            List<ThreadSnapshot> group = null;
            if (!stack.isEmpty() && grouping == ThreadGrouping.SAME_STACK) {
                group = groupsByStack.get(stack);
            } else if (!stack.isEmpty() && grouping == ThreadGrouping.NEIGHBORS && !groups.isEmpty()) {
                List<ThreadSnapshot> last = groups.get(groups.size() - 1);
                group = stack.equals(Arrays.asList(last.get(0).stack())) ? last : null;
            }
            if (group == null) {
                group = new ArrayList<>();
                groups.add(group);
                if (!stack.isEmpty() && grouping == ThreadGrouping.SAME_STACK) {
                    groupsByStack.put(stack, group);
                }
            }
            group.add(thread);
        }
        return groups;
    }

    /**
     * Counts the threads per state, such as {@code 12 RUNNABLE, 43 BLOCKED}, in {@link Thread.State} order and
     * without the states that have no threads.
     */
    static String formatStateCounts(Collection<ThreadSnapshot> threads) {
        Map<Thread.State, Integer> counts = new EnumMap<>(Thread.State.class);
        for (ThreadSnapshot thread : threads) {
            counts.merge(thread.state(), 1, Integer::sum);
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Thread.State, Integer> entry : counts.entrySet()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(entry.getValue()).append(' ').append(entry.getKey());
        }
        return text.toString();
    }

    private static String formatHeader(ThreadSnapshot thread) {
        Thread liveThread = thread.thread;
        ThreadInfo info = thread.info;
        String name = thread.name;
        ThreadGroup group = liveThread.getThreadGroup();
        StringBuilder header = new StringBuilder(format(Locale.US, "\"%s\"%s priority=%d id=0x%x group=%s cpu=%s block_cnt=%d wait_cnt=%d %s",
                name, liveThread.isDaemon() ? " daemon" : "", liveThread.getPriority(), liveThread.getId(),
                group == null ? "-DEAD-" : group.getName(), formatCpuTime(thread.cpuNanos), info.getBlockedCount(),
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
        String threadToString = thread.toString;
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
