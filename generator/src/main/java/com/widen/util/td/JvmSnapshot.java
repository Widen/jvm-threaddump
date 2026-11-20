package com.widen.util.td;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the checks and sections read from the JVM, read once. A thread appears only when it is in both the live
 * thread set and the {@link ThreadMXBean} snapshot, so each header and stack come from one consistent moment.
 */
final class JvmSnapshot
{
    private static final Comparator<ThreadSnapshot> ORDER = Comparator
            .comparing((ThreadSnapshot snapshot) -> snapshot.name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(snapshot -> snapshot.name)
            .thenComparingLong(ThreadSnapshot::id);

    /** In dump order: name ignoring case, name, id. */
    final List<ThreadSnapshot> threads;
    /** Empty when the JVM reports no deadlocked threads. */
    final long[] deadlockedIds;
    /** The pools that report usage, in JVM order. */
    final List<PoolSnapshot> pools;
    final List<GcSnapshot> collectors;
    /** -1 when unavailable. */
    final double systemCpuLoad;
    /** -1 when unavailable. */
    final double processCpuLoad;
    final long uptimeMillis;
    /** The length of the sampling window in nanoseconds, or 0 when not sampled. */
    final long windowNanos;
    final boolean cpuTimeAvailable;
    final boolean allocationAvailable;
    final long generatingThreadId;

    private JvmSnapshot(List<ThreadSnapshot> threads, long[] deadlockedIds, List<PoolSnapshot> pools,
            List<GcSnapshot> collectors, double systemCpuLoad, double processCpuLoad, long uptimeMillis,
            long windowNanos, boolean cpuTimeAvailable, boolean allocationAvailable, long generatingThreadId) {
        this.threads = threads;
        this.deadlockedIds = deadlockedIds;
        this.pools = pools;
        this.collectors = collectors;
        this.systemCpuLoad = systemCpuLoad;
        this.processCpuLoad = processCpuLoad;
        this.uptimeMillis = uptimeMillis;
        this.windowNanos = windowNanos;
        this.cpuTimeAvailable = cpuTimeAvailable;
        this.allocationAvailable = allocationAvailable;
        this.generatingThreadId = generatingThreadId;
    }

    /**
     * Reads the JVM, pausing for {@code window} between a start and an end reading when it is above zero. A thread
     * that is already interrupted skips the window. An interrupt during the pause ends it early. Either way the
     * interrupt flag stays set, and the snapshot counts as sampled only when time passed.
     */
    static JvmSnapshot read(JvmSources jvm, Duration window) {
        ThreadMXBean threadBean = jvm.threads();
        com.sun.management.ThreadMXBean sunThreadBean = threadBean instanceof com.sun.management.ThreadMXBean
                ? (com.sun.management.ThreadMXBean) threadBean : null;
        OperatingSystemMXBean os = jvm.operatingSystem();
        com.sun.management.OperatingSystemMXBean sunOs = os instanceof com.sun.management.OperatingSystemMXBean
                ? (com.sun.management.OperatingSystemMXBean) os : null;
        boolean cpuTimeAvailable = threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled();
        boolean allocationAvailable = sunThreadBean != null && sunThreadBean.isThreadAllocatedMemorySupported()
                && sunThreadBean.isThreadAllocatedMemoryEnabled();

        WindowStart start = null;
        long windowNanos = 0;
        if (!window.isZero() && !window.isNegative() && !Thread.currentThread().isInterrupted()) {
            long startNanos = jvm.nanoTime();
            start = WindowStart.read(jvm, threadBean, cpuTimeAvailable, allocationAvailable ? sunThreadBean : null,
                    sunOs);
            try {
                jvm.pause(window);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            windowNanos = jvm.nanoTime() - startNanos;
        }
        boolean sampled = windowNanos > 0;

        long[] deadlockedIds = threadBean.findDeadlockedThreads();

        Map<Long, ThreadInfo> infos = new HashMap<>();
        for (ThreadInfo info : threadBean.dumpAllThreads(false, false)) {
            infos.put(info.getThreadId(), info);
        }
        List<ThreadSnapshot> threads = new ArrayList<>();
        for (Thread thread : jvm.liveThreads()) {
            ThreadInfo info = infos.get(thread.getId());
            if (info != null) {
                String name = thread.getName();
                String toString = thread.toString();
                long cpuNanos = cpuTimeAvailable ? threadBean.getThreadCpuTime(thread.getId()) : -1;
                long windowCpuNanos = -1;
                long windowAllocatedBytes = -1;
                if (sampled) {
                    windowCpuNanos = cpuTimeAvailable ? windowDelta(cpuNanos, start.cpuNanos, thread.getId()) : -1;
                    windowAllocatedBytes = allocationAvailable
                            ? windowDelta(sunThreadBean.getThreadAllocatedBytes(thread.getId()), start.allocatedBytes,
                                    thread.getId())
                            : -1;
                }
                threads.add(new ThreadSnapshot(thread, info, name, toString, cpuNanos, windowCpuNanos,
                        windowAllocatedBytes));
            }
        }
        threads.sort(ORDER);

        List<PoolSnapshot> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : jvm.memoryPools()) {
            MemoryUsage usage = pool.getUsage();
            if (usage != null) {
                pools.add(new PoolSnapshot(pool.getName(), pool.getType(), usage, pool.getCollectionUsage()));
            }
        }

        List<GcSnapshot> collectors = new ArrayList<>();
        for (GarbageCollectorMXBean collector : jvm.garbageCollectors()) {
            String name = collector.getName();
            long count = collector.getCollectionCount();
            long timeMillis = collector.getCollectionTime();
            long windowCount = 0;
            long windowTimeMillis = 0;
            long[] before = sampled ? start.collectors.get(name) : null;
            if (before != null) {
                windowCount = count >= 0 && before[0] >= 0 ? count - before[0] : 0;
                windowTimeMillis = timeMillis >= 0 && before[1] >= 0 ? timeMillis - before[1] : 0;
            }
            collectors.add(new GcSnapshot(name, count, timeMillis, windowCount, windowTimeMillis));
        }

        double systemCpuLoad = -1;
        double processCpuLoad = -1;
        if (sunOs != null) {
            systemCpuLoad = sunOs.getSystemCpuLoad();
            processCpuLoad = sunOs.getProcessCpuLoad();
        }

        return new JvmSnapshot(
                Collections.unmodifiableList(threads),
                deadlockedIds == null ? new long[0] : deadlockedIds,
                Collections.unmodifiableList(pools),
                Collections.unmodifiableList(collectors),
                systemCpuLoad,
                processCpuLoad,
                jvm.runtime().getUptime(),
                sampled ? windowNanos : 0,
                cpuTimeAvailable,
                allocationAvailable,
                Thread.currentThread().getId());
    }

    boolean sampled() {
        return windowNanos > 0;
    }

    /**
     * The growth of a counter over the window. A thread missing from the start reading counts its whole total, and a
     * counter that is unavailable at either end gives -1.
     */
    private static long windowDelta(long end, Map<Long, Long> startValues, long threadId) {
        if (end < 0) {
            return -1;
        }
        Long before = startValues.get(threadId);
        if (before == null) {
            return end;
        }
        return before < 0 ? -1 : end - before;
    }

    /** What the JVM reported before the pause. */
    private static final class WindowStart
    {
        final Map<Long, Long> cpuNanos = new HashMap<>();
        final Map<Long, Long> allocatedBytes = new HashMap<>();
        final Map<String, long[]> collectors = new HashMap<>();

        /**
         * @param readCpuTime whether to read CPU time from {@code threadBean}
         * @param allocationBean the bean to read allocated bytes from, or null to skip it
         * @param os the bean whose CPU loads start their measuring span, or null
         */
        static WindowStart read(JvmSources jvm, ThreadMXBean threadBean, boolean readCpuTime,
                com.sun.management.ThreadMXBean allocationBean, com.sun.management.OperatingSystemMXBean os) {
            WindowStart start = new WindowStart();
            for (long id : threadBean.getAllThreadIds()) {
                if (readCpuTime) {
                    start.cpuNanos.put(id, threadBean.getThreadCpuTime(id));
                }
                if (allocationBean != null) {
                    start.allocatedBytes.put(id, allocationBean.getThreadAllocatedBytes(id));
                }
            }
            for (GarbageCollectorMXBean collector : jvm.garbageCollectors()) {
                start.collectors.put(collector.getName(),
                        new long[] {collector.getCollectionCount(), collector.getCollectionTime()});
            }
            if (os != null) {
                os.getSystemCpuLoad();
                os.getProcessCpuLoad();
            }
            return start;
        }
    }
}
