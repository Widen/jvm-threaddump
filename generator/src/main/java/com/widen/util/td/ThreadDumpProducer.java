package com.widen.util.td;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

public class ThreadDumpProducer {

    private final static ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    private final static boolean cpuTimeEnabled = threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled();

    long[] getDeadlocks() {
        return threadBean.findMonitorDeadlockedThreads();
    }

    Map<Thread, StackTraceElement[]> getThreads() {
        Map<Thread, StackTraceElement[]> dump = new TreeMap<>(
                (Comparator<Object>) (lhs, rhs) -> {
                    Thread t1 = (Thread) lhs;
                    Thread t2 = (Thread) rhs;
                    String t1Name = t1.getName() + t1.getId(); //do not throw away duplicate named threads
                    String t2Name = t2.getName() + t2.getId();
                    return t1Name.compareToIgnoreCase(t2Name);
                });
        dump.putAll(Thread.getAllStackTraces());
        return dump;
    }

    static String formatThread(Thread thread) {
        String name = thread.getName();
        long threadId = thread.getId();
        Thread.State state = thread.getState();
        boolean daemon = thread.isDaemon();
        int priority = thread.getPriority();
        String threadGroupName = thread.getThreadGroup() == null ? "-DEAD-" : thread.getThreadGroup().getName();

        long cpuTimeNanos = cpuTimeEnabled ? threadBean.getThreadCpuTime(threadId) : -1;

        // get a ThreadInfo object from the thread MX bean
        ThreadInfo info = threadBean.getThreadInfo(threadId);
        long blockedCount = info == null ? -1 : info.getBlockedCount();
        long waitedCount = info == null ? -1 : info.getWaitedCount();
        long lockOwnerId = info == null ? -1 : info.getLockOwnerId();
        String lockName = info == null ? "" : info.getLockName();

        String threadToString = thread.toString();

        StringBuilder sb = new StringBuilder("\"" + name + "\"");

        if (daemon) {
            sb.append(" daemon");
        }
        sb.append(" priority=" + priority);
        sb.append(" id=0x" + Long.toHexString(threadId));
        sb.append(" group=" + threadGroupName);

        if (cpuTimeNanos != -1) {
            sb.append(" cpu=" + getThreadCpuTimeString(cpuTimeNanos));
        }

        sb.append(" block_cnt=" + blockedCount);
        sb.append(" wait_cnt=" + waitedCount);
        sb.append(" " + state);

        if (lockOwnerId != -1) {
            sb.append(" (waiting on 0x" + Long.toHexString(lockOwnerId));
            if (lockName != null) {
                sb.append(" for " + lockName);
            }
            sb.append(")");
        }

        // threadToString - skip if standard Thread.toString() or same as thread name
        if (threadToString != null && !threadToString.startsWith("Thread[") && !threadToString.equalsIgnoreCase(name)) {
            sb.append(" (" + threadToString + ")");
        }

        return sb.toString();
    }

    private static String getThreadCpuTimeString(long cpuTimeNanos) {
        long cpuTimeMs = cpuTimeNanos / 1000000;
        if (cpuTimeMs < 10000) {
            return cpuTimeMs + "ms";
        } else {
            long cpuTimeSeconds = cpuTimeMs / 1000;
            return cpuTimeSeconds + "s";
        }
    }

    static String formatFrame(StackTraceElement frame) {
        String className = frame.getClassName();
        String methodName = frame.getMethodName();
        String fileName = frame.getFileName();        // null if unavailable
        int lineNumber = frame.getLineNumber();    // negative if unavailable
        boolean isNative = frame.isNativeMethod();

        StringBuilder sb = new StringBuilder(className + "." + methodName);
        if (fileName != null) {
            sb.append("(" + fileName);
            if (lineNumber > 0) {
                sb.append(":" + lineNumber);
            }
            sb.append(")");
        } else {
            if (isNative) {
                sb.append("(Native Method)");
            } else {
                sb.append("(Unknown Source)");
            }
        }

        return "      " + sb.toString();
    }

}
