package com.widen.util.td;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static com.widen.util.td.FakeJvm.MB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestJvmSnapshot
{
    private static final MemoryUsage USAGE = new MemoryUsage(MB, 2 * MB, 4 * MB, 8 * MB);

    @Test
    void readsEachSourceOnce() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("main", 1, Thread.State.RUNNABLE);
        jvm.addThread("worker", 2, Thread.State.WAITING);
        MemoryPoolMXBean pool = jvm.addPool("G1 Old Gen", MemoryType.HEAP, USAGE, null);
        GarbageCollectorMXBean collector = jvm.addCollector("G1 Young Generation", 12, 1234);

        JvmSnapshot.read(jvm, Duration.ZERO);

        verify(jvm.threads, times(1)).dumpAllThreads(false, false);
        verify(jvm.threads, times(1)).findDeadlockedThreads();
        verify(pool, times(1)).getUsage();
        verify(collector, times(1)).getCollectionTime();
        com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) jvm.operatingSystem;
        verify(os, times(1)).getSystemCpuLoad();
        verify(os, times(1)).getProcessCpuLoad();
    }

    @Test
    void holdsThreadsInDumpOrderWithTheirCpuTime() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("b", 5, Thread.State.RUNNABLE);
        jvm.addThread("A", 6, Thread.State.RUNNABLE);
        when(jvm.threads.isThreadCpuTimeSupported()).thenReturn(true);
        when(jvm.threads.isThreadCpuTimeEnabled()).thenReturn(true);
        when(jvm.threads.getThreadCpuTime(6)).thenReturn(1_500_000_000L);

        JvmSnapshot snapshot = jvm.snapshot();

        assertThat(snapshot.threads.stream().map(thread -> thread.name).collect(Collectors.toList()))
                .containsExactly("A", "b");
        assertThat(snapshot.threads.get(0).cpuNanos).isEqualTo(1_500_000_000L);
        assertThat(snapshot.threads.get(0).windowCpuNanos).isEqualTo(-1);
        assertThat(snapshot.cpuTimeAvailable).isTrue();
    }

    @Test
    void marksCpuTimeUnavailableWhenDisabled() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("main", 1, Thread.State.RUNNABLE);
        when(jvm.threads.isThreadCpuTimeSupported()).thenReturn(true);
        when(jvm.threads.isThreadCpuTimeEnabled()).thenReturn(false);

        JvmSnapshot snapshot = jvm.snapshot();

        assertThat(snapshot.threads.get(0).cpuNanos).isEqualTo(-1);
        assertThat(snapshot.cpuTimeAvailable).isFalse();
    }

    @Test
    void leavesOutAPoolWithoutUsageAndKeepsCollectionUsage() {
        FakeJvm jvm = new FakeJvm();
        MemoryUsage collectionUsage = new MemoryUsage(0, MB, 2 * MB, 4 * MB);
        jvm.addPool("G1 Old Gen", MemoryType.HEAP, USAGE, collectionUsage);
        jvm.addPool("G1 Survivor Space", MemoryType.HEAP, null, null);

        JvmSnapshot snapshot = jvm.snapshot();

        assertThat(snapshot.pools).singleElement().satisfies(pool -> {
            assertThat(pool.name).isEqualTo("G1 Old Gen");
            assertThat(pool.type).isEqualTo(MemoryType.HEAP);
            assertThat(pool.usage).isEqualTo(USAGE);
            assertThat(pool.collectionUsage).isEqualTo(collectionUsage);
        });
    }

    @Test
    void reportsUnavailableCpuLoadAsMinusOne() {
        FakeJvm jvm = new FakeJvm();
        jvm.operatingSystem = mock(OperatingSystemMXBean.class);

        JvmSnapshot snapshot = jvm.snapshot();

        assertThat(snapshot.systemCpuLoad).isEqualTo(-1);
        assertThat(snapshot.processCpuLoad).isEqualTo(-1);
    }

    @Test
    void holdsNoDeadlockedIdsWhenTheJvmReportsNone() {
        assertThat(new FakeJvm().snapshot().deadlockedIds).isEmpty();
    }

    @Test
    void holdsDeadlocksCollectorsUptimeAndTheCallingThread() {
        FakeJvm jvm = new FakeJvm();
        jvm.addCollector("G1 Young Generation", 12, 1234);
        when(jvm.threads.findDeadlockedThreads()).thenReturn(new long[] {3, 26});

        JvmSnapshot snapshot = jvm.snapshot();

        assertThat(snapshot.deadlockedIds).containsExactly(3, 26);
        assertThat(snapshot.collectors).singleElement().satisfies(collector -> {
            assertThat(collector.name).isEqualTo("G1 Young Generation");
            assertThat(collector.count).isEqualTo(12);
            assertThat(collector.timeMillis).isEqualTo(1234);
        });
        assertThat(snapshot.uptimeMillis).isEqualTo(90_061_000L);
        assertThat(snapshot.generatingThreadId).isEqualTo(Thread.currentThread().getId());
        assertThat(snapshot.sampled()).isFalse();
    }
}
