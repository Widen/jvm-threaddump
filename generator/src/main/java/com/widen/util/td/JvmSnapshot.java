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
import java.util.LinkedHashMap;
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
    /** The ids the JVM reports as deadlocked, empty when it reports none. See {@link LockGraph#deadlockedIds()}. */
    final long[] jvmDeadlockedIds;
    /** The pools that report usage, in JVM order. */
    final List<PoolSnapshot> pools;
    final List<GcSnapshot> collectors;
    /** -1 when unavailable. */
    final double systemCpuLoad;
    /** -1 when unavailable. */
    final double processCpuLoad;
    /** -1 unless the operating system bean is a {@link com.sun.management.UnixOperatingSystemMXBean}. */
    final long openFileDescriptors;
    /** -1 unless the operating system bean is a {@link com.sun.management.UnixOperatingSystemMXBean}. */
    final long maxFileDescriptors;
    /** The container's CPU limit in CPUs, or -1 when it has none. */
    final double cpuLimit;
    /** The length of one CPU period in microseconds, or 0 without a limit. */
    final long cpuPeriodMicros;
    /** The periods in which the container had work to run, or 0 without a limit. */
    final long cpuPeriods;
    /** The periods the container was throttled in, or 0 without a limit. */
    final long cpuThrottledPeriods;
    /**
     * Whether the window counts are measured: the dump is sampled, both readings have a limit, and neither counter went
     * down.
     */
    final boolean cpuThrottlingSampled;
    /** The periods inside the sampling window, or 0 unless {@link #cpuThrottlingSampled}. */
    final long windowCpuPeriods;
    /** The throttled periods inside the sampling window, or 0 unless {@link #cpuThrottlingSampled}. */
    final long windowCpuThrottledPeriods;
    final long uptimeMillis;
    /** The length of the sampling window in nanoseconds, or 0 when not sampled. */
    final long windowNanos;
    final boolean cpuTimeAvailable;
    final boolean allocationAvailable;
    final long generatingThreadId;
    private final LockGraph locks;
    private final Map<Long, Integer> positionsById;

    private JvmSnapshot(List<ThreadSnapshot> threads, long[] jvmDeadlockedIds, List<PoolSnapshot> pools,
            List<GcSnapshot> collectors, double systemCpuLoad, double processCpuLoad, long openFileDescriptors,
            long maxFileDescriptors, CgroupCpu cgroupCpu, boolean cpuThrottlingSampled, long windowCpuPeriods,
            long windowCpuThrottledPeriods, long uptimeMillis, long windowNanos, boolean cpuTimeAvailable, boolean allocationAvailable,
            long generatingThreadId) {
        this.threads = threads;
        this.jvmDeadlockedIds = jvmDeadlockedIds;
        this.pools = pools;
        this.collectors = collectors;
        this.systemCpuLoad = systemCpuLoad;
        this.processCpuLoad = processCpuLoad;
        this.openFileDescriptors = openFileDescriptors;
        this.maxFileDescriptors = maxFileDescriptors;
        this.cpuLimit = cgroupCpu == null ? -1 : cgroupCpu.limitCpus;
        this.cpuPeriodMicros = cgroupCpu == null ? 0 : cgroupCpu.periodMicros;
        this.cpuPeriods = cgroupCpu == null ? 0 : cgroupCpu.periods;
        this.cpuThrottledPeriods = cgroupCpu == null ? 0 : cgroupCpu.throttledPeriods;
        this.cpuThrottlingSampled = cpuThrottlingSampled;
        this.windowCpuPeriods = windowCpuPeriods;
        this.windowCpuThrottledPeriods = windowCpuThrottledPeriods;
        this.uptimeMillis = uptimeMillis;
        this.windowNanos = windowNanos;
        this.cpuTimeAvailable = cpuTimeAvailable;
        this.allocationAvailable = allocationAvailable;
        this.generatingThreadId = generatingThreadId;
        this.locks = LockGraph.of(threads, jvmDeadlockedIds);

        Map<Long, Integer> positionsById = new HashMap<>();
        for (int i = 0; i < threads.size(); i++) {
            positionsById.putIfAbsent(threads.get(i).id(), i);
        }
        this.positionsById = Collections.unmodifiableMap(positionsById);
    }

    /**
     * Reads the JVM, pausing for {@code window} between a start and an end reading when it is above zero. A thread
     * that is already interrupted skips the window. An interrupt during the pause ends it early and leaves the
     * snapshot not sampled and its CPU loads unavailable, because a rate over a partial window misleads. Either way
     * the interrupt flag stays set. The snapshot also counts as not sampled when no time passed.
     */
    static JvmSnapshot read(JvmSources jvm, Duration window) {
        ThreadMXBean threadBean = jvm.threads();
        com.sun.management.ThreadMXBean sunThreadBean = SunManagement.threads(threadBean);
        OperatingSystemMXBean os = jvm.operatingSystem();
        com.sun.management.OperatingSystemMXBean sunOs = SunManagement.operatingSystem(os);
        boolean cpuTimeAvailable = threadBean.isThreadCpuTimeSupported() && threadBean.isThreadCpuTimeEnabled();
        boolean allocationAvailable = sunThreadBean != null && sunThreadBean.isThreadAllocatedMemorySupported()
                && sunThreadBean.isThreadAllocatedMemoryEnabled();

        Reading start = null;
        long windowNanos = 0;
        boolean interruptedInWindow = false;
        if (!window.isZero() && !Thread.currentThread().isInterrupted()) {
            long startNanos = jvm.nanoTime();
            start = Reading.read(jvm, threadBean, cpuTimeAvailable, allocationAvailable ? sunThreadBean : null, sunOs);
            try {
                jvm.pause(window);
                windowNanos = jvm.nanoTime() - startNanos;
            }
            catch (InterruptedException e) {
                interruptedInWindow = true;
                Thread.currentThread().interrupt();
            }
        }
        boolean sampled = windowNanos > 0;
        // Read before the thread dump, whose safepoints would otherwise add their time to every rate but not to
        // windowNanos. Allocation only ever prints as a rate, so a dump that is not sampled skips it.
        Reading end = Reading.read(jvm, threadBean, cpuTimeAvailable,
                sampled && allocationAvailable ? sunThreadBean : null, sunOs);

        Map<Long, ThreadInfo> infos = new HashMap<>();
        for (ThreadInfo info : threadBean.dumpAllThreads(false, false)) {
            infos.put(info.getThreadId(), info);
        }
        // Asked after the dump, so the JVM reports every permanent cycle that the dump shows.
        long[] jvmDeadlockedIds = threadBean.findDeadlockedThreads();

        List<ThreadSnapshot> threads = new ArrayList<>();
        for (Thread thread : jvm.liveThreads()) {
            long id = thread.getId();
            ThreadInfo info = infos.get(id);
            if (info != null) {
                String name = thread.getName();
                String toString = thread.toString();
                long cpuNanos = end.cpuNanos.getOrDefault(id, -1L);
                long windowCpuNanos = sampled ? windowDelta(end.cpuNanos, start.cpuNanos, id) : -1;
                long windowAllocatedBytes = sampled ? windowDelta(end.allocatedBytes, start.allocatedBytes, id) : -1;
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
        for (Map.Entry<String, long[]> collector : end.collectors.entrySet()) {
            String name = collector.getKey();
            long count = collector.getValue()[0];
            long timeMillis = collector.getValue()[1];
            long windowCount = 0;
            long windowTimeMillis = 0;
            long[] before = sampled ? start.collectors.get(name) : null;
            if (before != null) {
                windowCount = count >= 0 && before[0] >= 0 ? count - before[0] : 0;
                windowTimeMillis = timeMillis >= 0 && before[1] >= 0 ? timeMillis - before[1] : 0;
            }
            collectors.add(new GcSnapshot(name, count, timeMillis, windowCount, windowTimeMillis));
        }

        long openFileDescriptors = -1;
        long maxFileDescriptors = -1;
        com.sun.management.UnixOperatingSystemMXBean unixOs = SunManagement.unixOperatingSystem(os);
        if (unixOs != null) {
            openFileDescriptors = unixOs.getOpenFileDescriptorCount();
            maxFileDescriptors = unixOs.getMaxFileDescriptorCount();
        }

        boolean cpuThrottlingSampled = false;
        long windowCpuPeriods = 0;
        long windowCpuThrottledPeriods = 0;
        if (sampled && start.cgroupCpu != null && end.cgroupCpu != null) {
            long periods = end.cgroupCpu.periods - start.cgroupCpu.periods;
            long throttledPeriods = end.cgroupCpu.throttledPeriods - start.cgroupCpu.throttledPeriods;
            // A counter that went down was reset, so the window has no figures to compare.
            if (periods >= 0 && throttledPeriods >= 0) {
                cpuThrottlingSampled = true;
                windowCpuPeriods = periods;
                windowCpuThrottledPeriods = throttledPeriods;
            }
        }

        return new JvmSnapshot(
                Collections.unmodifiableList(threads),
                jvmDeadlockedIds == null ? new long[0] : jvmDeadlockedIds,
                Collections.unmodifiableList(pools),
                Collections.unmodifiableList(collectors),
                interruptedInWindow ? -1 : end.systemCpuLoad,
                interruptedInWindow ? -1 : end.processCpuLoad,
                openFileDescriptors,
                maxFileDescriptors,
                end.cgroupCpu,
                cpuThrottlingSampled,
                windowCpuPeriods,
                windowCpuThrottledPeriods,
                jvm.runtime().getUptime(),
                windowNanos,
                cpuTimeAvailable,
                allocationAvailable,
                Thread.currentThread().getId());
    }

    boolean sampled() {
        return windowNanos > 0;
    }

    /** Who waits on whom for a lock, built once from {@link #threads}. */
    LockGraph locks() {
        return locks;
    }

    /** Returns the thread with the id, or null when the snapshot holds none. */
    ThreadSnapshot thread(long id) {
        Integer position = positionsById.get(id);
        return position == null ? null : threads.get(position);
    }

    /**
     * Returns the index of the thread with the id in {@link #threads}, or {@code threads.size()} when the snapshot
     * holds none, so a missing thread sorts after every present one.
     */
    int position(long id) {
        Integer position = positionsById.get(id);
        return position == null ? threads.size() : position;
    }

    /**
     * The growth of a counter over the window. A thread missing from the start reading counts its whole total, and a
     * counter that is unavailable at either end, or a thread missing from the end reading, gives -1.
     */
    private static long windowDelta(Map<Long, Long> endValues, Map<Long, Long> startValues, long threadId) {
        Long end = endValues.get(threadId);
        if (end == null || end < 0) {
            return -1;
        }
        Long before = startValues.get(threadId);
        if (before == null) {
            return end;
        }
        return before < 0 ? -1 : end - before;
    }

    /** The counters the JVM reported at one end of the window. A dump that is not sampled takes one, for totals. */
    private static final class Reading
    {
        /** Total CPU time by thread id, empty when not read. */
        final Map<Long, Long> cpuNanos = new HashMap<>();
        /** Total allocated bytes by thread id, empty when not read. */
        final Map<Long, Long> allocatedBytes = new HashMap<>();
        /** The collection count and time in milliseconds by collector name, in JVM order. */
        final Map<String, long[]> collectors = new LinkedHashMap<>();
        /** The load since the previous reading, or -1 when unavailable. */
        double systemCpuLoad = -1;
        /** The load since the previous reading, or -1 when unavailable. */
        double processCpuLoad = -1;
        /** Null when the container has no CPU limit. */
        CgroupCpu cgroupCpu;

        /**
         * @param readCpuTime whether to read CPU time from {@code threadBean}
         * @param allocationBean the bean to read allocated bytes from, or null to skip it
         * @param os the bean to read the CPU loads from, or null to skip them
         */
        static Reading read(JvmSources jvm, ThreadMXBean threadBean, boolean readCpuTime,
                com.sun.management.ThreadMXBean allocationBean, com.sun.management.OperatingSystemMXBean os) {
            Reading reading = new Reading();
            if (readCpuTime || allocationBean != null) {
                for (long id : threadBean.getAllThreadIds()) {
                    if (readCpuTime) {
                        reading.cpuNanos.put(id, threadBean.getThreadCpuTime(id));
                    }
                    if (allocationBean != null) {
                        reading.allocatedBytes.put(id, allocationBean.getThreadAllocatedBytes(id));
                    }
                }
            }
            for (GarbageCollectorMXBean collector : jvm.garbageCollectors()) {
                reading.collectors.put(collector.getName(),
                        new long[] {collector.getCollectionCount(), collector.getCollectionTime()});
            }
            if (os != null) {
                reading.systemCpuLoad = os.getSystemCpuLoad();
                reading.processCpuLoad = os.getProcessCpuLoad();
            }
            reading.cgroupCpu = CgroupCpu.read(jvm);
            return reading;
        }
    }
}
