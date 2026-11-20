package com.widen.util.td;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link JvmSources} with fixed data. Tests change the fields before building the code under test.
 */
final class FakeJvm implements JvmSources
{
    static final long MB = 1024L * 1024L;
    static final long GB = 1024L * MB;

    RuntimeMXBean runtime = mockRuntime();
    MemoryMXBean memory = mockMemory();
    final List<ThreadInfo> threadInfos = new ArrayList<>();
    com.sun.management.ThreadMXBean threads = mockThreads();
    List<MemoryPoolMXBean> memoryPools = new ArrayList<>();
    List<GarbageCollectorMXBean> garbageCollectors = new ArrayList<>();
    OperatingSystemMXBean operatingSystem = mockOperatingSystem();
    Clock clock = Clock.fixed(Instant.parse("2026-11-20T15:30:00Z"), ZoneOffset.UTC);
    TimeZone timeZone = TimeZone.getTimeZone("UTC");
    Map<String, String> environment = new HashMap<>();
    Map<String, String> systemProperties = new HashMap<>();
    long freeMemory = 100 * MB;
    long maxMemory = 512 * MB;
    Optional<String> procUptime = Optional.of("93784.56 12345.67\n");
    Set<Thread> liveThreads = new HashSet<>();
    long nanoTime;
    int pauses;
    /** When set, {@code pause} advances the clock by this much and throws {@link InterruptedException}. */
    Duration interruptPauseAfter;

    FakeJvm() {
        environment.put("HOSTNAME", "app-1");
        systemProperties.put("java.runtime.version", "1.8.0_472-b08");
        systemProperties.put("java.vendor", "Example Vendor");
        systemProperties.put("java.vm.version", "25.472-b08");
        systemProperties.put("java.vm.info", "mixed mode");
        systemProperties.put("java.home", "/opt/java");
        systemProperties.put("user.dir", "/srv/app");
        systemProperties.put("java.io.tmpdir", "/tmp");
        systemProperties.put("file.encoding", "UTF-8");
    }

    private static RuntimeMXBean mockRuntime() {
        RuntimeMXBean bean = mock(RuntimeMXBean.class);
        when(bean.getInputArguments()).thenReturn(Arrays.asList("-Dfile.encoding=UTF-8", "-Xmx512m"));
        when(bean.getUptime()).thenReturn(90_061_000L);
        return bean;
    }

    private static MemoryMXBean mockMemory() {
        MemoryMXBean bean = mock(MemoryMXBean.class);
        when(bean.getHeapMemoryUsage()).thenReturn(new MemoryUsage(256 * MB, 64 * MB, 128 * MB, 256 * MB));
        when(bean.getNonHeapMemoryUsage()).thenReturn(new MemoryUsage(2 * MB, 8 * MB, 16 * MB, -1));
        return bean;
    }

    private com.sun.management.ThreadMXBean mockThreads() {
        com.sun.management.ThreadMXBean bean = mock(com.sun.management.ThreadMXBean.class);
        when(bean.findDeadlockedThreads()).thenReturn(null);
        when(bean.dumpAllThreads(anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> threadInfos.toArray(new ThreadInfo[0]));
        when(bean.getAllThreadIds())
                .thenAnswer(invocation -> liveThreads.stream().mapToLong(Thread::getId).toArray());
        when(bean.isThreadCpuTimeSupported()).thenReturn(false);
        when(bean.isCurrentThreadCpuTimeSupported()).thenReturn(false);
        return bean;
    }

    private static OperatingSystemMXBean mockOperatingSystem() {
        com.sun.management.OperatingSystemMXBean bean = mock(com.sun.management.OperatingSystemMXBean.class);
        when(bean.getName()).thenReturn("Linux");
        when(bean.getVersion()).thenReturn("5.10");
        when(bean.getAvailableProcessors()).thenReturn(4);
        when(bean.getArch()).thenReturn("amd64");
        when(bean.getFreePhysicalMemorySize()).thenReturn(GB);
        when(bean.getTotalPhysicalMemorySize()).thenReturn(4 * GB);
        when(bean.getSystemCpuLoad()).thenReturn(0.25);
        when(bean.getProcessCpuLoad()).thenReturn(0.125);
        when(bean.getProcessCpuTime()).thenReturn(61_000_000_000L);
        return bean;
    }

    /**
     * Adds a live thread and the matching {@link ThreadInfo} to what {@code dumpAllThreads} returns.
     */
    ThreadInfo addThread(String name, long id, Thread.State state, StackTraceElement... frames) {
        liveThreads.add(FakeThreads.thread(name, id));
        ThreadInfo info = FakeThreads.info(id, state, frames);
        threadInfos.add(info);
        return info;
    }

    MemoryPoolMXBean addPool(String name, MemoryType type, MemoryUsage usage, MemoryUsage collectionUsage) {
        MemoryPoolMXBean pool = mock(MemoryPoolMXBean.class);
        when(pool.getName()).thenReturn(name);
        when(pool.getType()).thenReturn(type);
        when(pool.getUsage()).thenReturn(usage);
        when(pool.getCollectionUsage()).thenReturn(collectionUsage);
        memoryPools.add(pool);
        return pool;
    }

    GarbageCollectorMXBean addCollector(String name, long count, long timeMillis) {
        GarbageCollectorMXBean collector = mock(GarbageCollectorMXBean.class);
        when(collector.getName()).thenReturn(name);
        when(collector.getCollectionCount()).thenReturn(count);
        when(collector.getCollectionTime()).thenReturn(timeMillis);
        garbageCollectors.add(collector);
        return collector;
    }

    JvmSnapshot snapshot() {
        return JvmSnapshot.read(this, Duration.ZERO);
    }

    @Override
    public RuntimeMXBean runtime() {
        return runtime;
    }

    @Override
    public MemoryMXBean memory() {
        return memory;
    }

    @Override
    public ThreadMXBean threads() {
        return threads;
    }

    @Override
    public List<MemoryPoolMXBean> memoryPools() {
        return memoryPools;
    }

    @Override
    public List<GarbageCollectorMXBean> garbageCollectors() {
        return garbageCollectors;
    }

    @Override
    public OperatingSystemMXBean operatingSystem() {
        return operatingSystem;
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public TimeZone timeZone() {
        return timeZone;
    }

    @Override
    public String environmentVariable(String name) {
        return environment.get(name);
    }

    @Override
    public String systemProperty(String name) {
        return systemProperties.get(name);
    }

    @Override
    public long freeMemory() {
        return freeMemory;
    }

    @Override
    public long maxMemory() {
        return maxMemory;
    }

    @Override
    public Optional<String> procUptime() {
        return procUptime;
    }

    @Override
    public Set<Thread> liveThreads() {
        return liveThreads;
    }

    @Override
    public void pause(Duration duration) throws InterruptedException {
        pauses++;
        if (interruptPauseAfter != null) {
            nanoTime += interruptPauseAfter.toNanos();
            throw new InterruptedException();
        }
        nanoTime += duration.toNanos();
    }

    @Override
    public long nanoTime() {
        return nanoTime;
    }
}
