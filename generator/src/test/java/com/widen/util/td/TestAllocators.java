package com.widen.util.td;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static com.widen.util.td.FakeJvm.MB;
import static org.assertj.core.api.Assertions.assertThat;

public class TestAllocators
{
    private static FakeJvm jvmWithAllocators() {
        FakeJvm jvm = new FakeJvm();
        jvm.enableAllocation();
        jvm.addAllocatingThread("import-7", 0x33, 412 * MB);
        jvm.addAllocatingThread("qtp-4", 0x49, 96 * MB);
        jvm.addAllocatingThread("c", 0x3, 50 * MB);
        jvm.addAllocatingThread("d", 0x4, 2 * MB);
        jvm.addAllocatingThread("e", 0x5, MB / 2);
        return jvm;
    }

    @Test
    void listsTheTopThreeAllocatorsByRate() {
        FakeJvm jvm = jvmWithAllocators();

        assertThat(Allocators.lines(jvm.snapshot(Duration.ofSeconds(1)))).containsExactly(
                "Top allocators: \"import-7\" (0x33) 412 MB/s",
                "                \"qtp-4\" (0x49) 96 MB/s",
                "                \"c\" (0x3) 50 MB/s");
    }

    @Test
    void dividesByTheLengthOfTheWindow() {
        FakeJvm jvm = jvmWithAllocators();

        assertThat(Allocators.lines(jvm.snapshot(Duration.ofSeconds(2)))).containsExactly(
                "Top allocators: \"import-7\" (0x33) 206 MB/s",
                "                \"qtp-4\" (0x49) 48 MB/s",
                "                \"c\" (0x3) 25 MB/s");
    }

    @Test
    void leavesOutThreadsBelowOneMegabytePerSecond() {
        FakeJvm jvm = new FakeJvm();
        jvm.enableAllocation();
        jvm.addAllocatingThread("quiet", 1, MB / 2);

        assertThat(Allocators.lines(jvm.snapshot(Duration.ofSeconds(1)))).isEmpty();
    }

    @Test
    void isEmptyWhenNoWindowRan() {
        assertThat(Allocators.lines(jvmWithAllocators().snapshot(Duration.ZERO))).isEmpty();
    }

    @Test
    void isEmptyWhenAllocationIsUnavailable() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("import-7", 0x33, Thread.State.RUNNABLE);

        assertThat(Allocators.lines(jvm.snapshot(Duration.ofSeconds(1)))).isEmpty();
    }
}
