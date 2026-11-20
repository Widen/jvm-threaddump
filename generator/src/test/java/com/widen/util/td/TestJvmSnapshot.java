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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void reportsWhatChangedDuringTheWindow() {
        FakeJvm jvm = jvmWithCpuAndAllocation();
        jvm.addThread("a", 23, Thread.State.RUNNABLE);
        jvm.addThread("c", 25, Thread.State.RUNNABLE);
        when(jvm.threads.getAllThreadIds()).thenReturn(new long[] {23, 24});
        when(jvm.threads.getThreadCpuTime(23)).thenReturn(1_000_000_000L, 1_600_000_000L);
        when(jvm.threads.getThreadCpuTime(25)).thenReturn(200_000_000L);
        when(jvm.threads.getThreadAllocatedBytes(23)).thenReturn(10 * MB, 110 * MB);
        when(jvm.threads.getThreadAllocatedBytes(25)).thenReturn(5 * MB);
        GarbageCollectorMXBean collector = jvm.addCollector("G1 Young Generation", 5, 100);
        when(collector.getCollectionCount()).thenReturn(5L, 7L);
        when(collector.getCollectionTime()).thenReturn(100L, 400L);
        GarbageCollectorMXBean undefined = jvm.addCollector("Undefined", -1, -1);
        when(undefined.getCollectionCount()).thenReturn(-1L, 3L);
        when(undefined.getCollectionTime()).thenReturn(-1L, 50L);
        com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) jvm.operatingSystem;
        when(os.getSystemCpuLoad()).thenReturn(0.10, 0.95);
        when(os.getProcessCpuLoad()).thenReturn(0.05, 0.40);

        JvmSnapshot snapshot = JvmSnapshot.read(jvm, Duration.ofSeconds(1));

        assertThat(snapshot.windowNanos).isEqualTo(1_000_000_000L);
        assertThat(snapshot.sampled()).isTrue();
        assertThat(jvm.pauses).isEqualTo(1);
        assertThat(snapshot.threads).extracting(thread -> thread.id()).containsExactly(23L, 25L);
        assertThat(snapshot.threads.get(0).windowCpuNanos).isEqualTo(600_000_000L);
        assertThat(snapshot.threads.get(0).windowAllocatedBytes).isEqualTo(100 * MB);
        assertThat(snapshot.threads.get(1).windowCpuNanos).isEqualTo(200_000_000L);
        assertThat(snapshot.threads.get(1).windowAllocatedBytes).isEqualTo(5 * MB);
        GcSnapshot gc = snapshot.collectors.get(0);
        assertThat(gc.windowCount).isEqualTo(2);
        assertThat(gc.windowTimeMillis).isEqualTo(300);
        assertThat(gc.count).isEqualTo(7);
        assertThat(gc.timeMillis).isEqualTo(400);
        GcSnapshot undefinedAtStart = snapshot.collectors.get(1);
        assertThat(undefinedAtStart.windowCount).isEqualTo(0);
        assertThat(undefinedAtStart.windowTimeMillis).isEqualTo(0);
        assertThat(snapshot.systemCpuLoad).isEqualTo(0.95);
        assertThat(snapshot.processCpuLoad).isEqualTo(0.40);
    }

    @Test
    void doesNotPauseForAZeroWindow() {
        FakeJvm jvm = jvmWithCpuAndAllocation();
        jvm.addThread("main", 1, Thread.State.RUNNABLE);
        jvm.addThread("worker", 2, Thread.State.RUNNABLE);

        JvmSnapshot snapshot = JvmSnapshot.read(jvm, Duration.ZERO);

        assertThat(jvm.pauses).isEqualTo(0);
        assertThat(snapshot.sampled()).isFalse();
        assertThat(snapshot.threads.get(0).windowCpuNanos).isEqualTo(-1);
        verify(jvm.threads, times(1)).getThreadCpuTime(1);
        verify(jvm.threads, times(1)).getThreadCpuTime(2);
        verify(((com.sun.management.OperatingSystemMXBean) jvm.operatingSystem), times(1)).getSystemCpuLoad();
    }

    @Test
    void finishesWithTheTimeThatElapsedWhenInterrupted() {
        FakeJvm jvm = new FakeJvm();
        jvm.interruptPauseAfter = Duration.ofMillis(250);

        JvmSnapshot snapshot = JvmSnapshot.read(jvm, Duration.ofSeconds(1));

        assertThat(snapshot.windowNanos).isEqualTo(250_000_000L);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void isNotSampledWhenInterruptedAtOnce() {
        FakeJvm jvm = jvmWithCpuAndAllocation();
        jvm.addThread("main", 1, Thread.State.RUNNABLE);
        jvm.addCollector("G1 Young Generation", 5, 100);
        jvm.interruptPauseAfter = Duration.ZERO;

        JvmSnapshot snapshot = JvmSnapshot.read(jvm, Duration.ofSeconds(1));

        assertThat(Thread.interrupted()).isTrue();
        assertThat(snapshot.sampled()).isFalse();
        assertThat(snapshot.windowNanos).isEqualTo(0);
        assertThat(snapshot.threads.get(0).windowCpuNanos).isEqualTo(-1);
        assertThat(snapshot.threads.get(0).windowAllocatedBytes).isEqualTo(-1);
        assertThat(snapshot.collectors.get(0).windowCount).isEqualTo(0);
        assertThat(snapshot.collectors.get(0).windowTimeMillis).isEqualTo(0);
    }

    @Test
    void skipsTheWindowForAThreadThatIsAlreadyInterrupted() {
        FakeJvm jvm = jvmWithCpuAndAllocation();
        jvm.addThread("main", 1, Thread.State.RUNNABLE);
        Thread.currentThread().interrupt();

        JvmSnapshot snapshot = JvmSnapshot.read(jvm, Duration.ofSeconds(1));

        boolean stillInterrupted = Thread.interrupted();
        assertThat(jvm.pauses).isEqualTo(0);
        assertThat(snapshot.sampled()).isFalse();
        assertThat(snapshot.threads.get(0).windowCpuNanos).isEqualTo(-1);
        assertThat(stillInterrupted).isTrue();
    }

    @Test
    void leavesOutWindowCpuForAThreadWhoseTimeIsUnavailable() {
        FakeJvm jvm = jvmWithCpuAndAllocation();
        jvm.addThread("a", 23, Thread.State.RUNNABLE);
        when(jvm.threads.getAllThreadIds()).thenReturn(new long[] {23});
        when(jvm.threads.getThreadCpuTime(23)).thenReturn(1_000_000_000L, -1L);

        JvmSnapshot snapshot = JvmSnapshot.read(jvm, Duration.ofSeconds(1));

        assertThat(snapshot.threads.get(0).windowCpuNanos).isEqualTo(-1);
    }

    @Test
    void leavesOutAllocationWhenUnsupported() {
        FakeJvm jvm = jvmWithCpuAndAllocation();
        jvm.addThread("a", 23, Thread.State.RUNNABLE);
        when(jvm.threads.getAllThreadIds()).thenReturn(new long[] {23});
        when(jvm.threads.isThreadAllocatedMemorySupported()).thenReturn(false);

        JvmSnapshot snapshot = JvmSnapshot.read(jvm, Duration.ofSeconds(1));

        assertThat(snapshot.allocationAvailable).isFalse();
        assertThat(snapshot.threads.get(0).windowAllocatedBytes).isEqualTo(-1);
        verify(jvm.threads, never()).getThreadAllocatedBytes(anyLong());
    }

    @Test
    void readsFileDescriptorCountsOnlyFromAUnixBean() {
        FakeJvm unix = new FakeJvm();
        unix.unixOperatingSystem(412, 65_536);

        assertThat(unix.snapshot().openFileDescriptors).isEqualTo(412);
        assertThat(unix.snapshot().maxFileDescriptors).isEqualTo(65_536);
        assertThat(new FakeJvm().snapshot().openFileDescriptors).isEqualTo(-1);
        assertThat(new FakeJvm().snapshot().maxFileDescriptors).isEqualTo(-1);
    }

    private static FakeJvm jvmWithCpuAndAllocation() {
        FakeJvm jvm = new FakeJvm();
        when(jvm.threads.isThreadCpuTimeSupported()).thenReturn(true);
        when(jvm.threads.isThreadCpuTimeEnabled()).thenReturn(true);
        when(jvm.threads.isThreadAllocatedMemorySupported()).thenReturn(true);
        when(jvm.threads.isThreadAllocatedMemoryEnabled()).thenReturn(true);
        return jvm;
    }
}
