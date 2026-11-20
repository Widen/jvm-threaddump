package com.widen.util.td;

import java.lang.management.ThreadInfo;

/**
 * A live thread joined to its {@link ThreadInfo}, with its name, {@code toString()}, and CPU time read once, so a
 * thread that renames itself cannot change its sort position or header mid-dump.
 */
final class ThreadSnapshot
{
    final Thread thread;
    final ThreadInfo info;
    final String name;
    final String toString;
    /** Total CPU time, or -1 when unavailable. */
    final long cpuNanos;
    /** CPU time spent inside the sampling window, or -1 when not sampled. */
    final long windowCpuNanos;
    /** Bytes allocated inside the sampling window, or -1 when not sampled. */
    final long windowAllocatedBytes;

    ThreadSnapshot(Thread thread, ThreadInfo info, String name, String toString, long cpuNanos, long windowCpuNanos,
            long windowAllocatedBytes) {
        this.thread = thread;
        this.info = info;
        this.name = name;
        this.toString = toString;
        this.cpuNanos = cpuNanos;
        this.windowCpuNanos = windowCpuNanos;
        this.windowAllocatedBytes = windowAllocatedBytes;
    }

    long id() {
        return thread.getId();
    }

    Thread.State state() {
        return info.getThreadState();
    }

    StackTraceElement[] stack() {
        return info.getStackTrace();
    }
}
