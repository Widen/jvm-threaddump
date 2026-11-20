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

    static JvmSnapshot read(JvmSources jvm, Duration window) {
        ThreadMXBean threadBean = jvm.threads();
        boolean cpuTimeAvailable = threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled();
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
                threads.add(new ThreadSnapshot(thread, info, name, toString, cpuNanos, -1, -1));
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
            collectors.add(new GcSnapshot(collector.getName(), collector.getCollectionCount(),
                    collector.getCollectionTime(), 0, 0));
        }

        OperatingSystemMXBean os = jvm.operatingSystem();
        double systemCpuLoad = -1;
        double processCpuLoad = -1;
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOs = (com.sun.management.OperatingSystemMXBean) os;
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
                0,
                cpuTimeAvailable,
                false,
                Thread.currentThread().getId());
    }

    boolean sampled() {
        return windowNanos > 0;
    }
}
