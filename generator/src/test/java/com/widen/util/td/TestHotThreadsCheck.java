package com.widen.util.td;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class TestHotThreadsCheck
{
    private static final long MS = 1_000_000L;

    private static FakeJvm jvmWithCpuTime() {
        FakeJvm jvm = new FakeJvm();
        jvm.enableCpuTime();
        return jvm;
    }

    private static List<Finding> findingsOf(FakeJvm jvm) {
        return new HotThreadsCheck().findings(jvm.snapshot(Duration.ofSeconds(1)));
    }

    @Test
    void listsThreadsThatUsedHalfACore() {
        FakeJvm jvm = jvmWithCpuTime();
        jvm.addBusyThread("a", 1, 900 * MS,
                FakeThreads.frame("com.example.A", "spin"),
                FakeThreads.frame("$A_1a", "spin"),
                FakeThreads.frame("com.example.A", "run"),
                FakeThreads.frame("com.example.A", "loop"),
                FakeThreads.frame("com.example.Main", "main"));
        jvm.addBusyThread("b", 2, 500 * MS);
        jvm.addBusyThread("c", 3, 499 * MS);

        List<Finding> findings = findingsOf(jvm);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).title).isEqualTo("Hot Threads");
        assertThat(findings.get(0).lines).containsExactly(
                "2 threads used 50% or more of one core over the last 1.0s",
                "\"a\" (0x1) 90% of one core",
                "    com.example.A.spin(A.java:1)",
                "    com.example.A.run(A.java:1)",
                "    com.example.A.loop(A.java:1)",
                "\"b\" (0x2) 50% of one core");
        assertThat(findings.get(0).marks).containsExactly("thread:1", "thread:2");
    }

    @Test
    void countsOneThreadInTheSingular() {
        FakeJvm jvm = jvmWithCpuTime();
        jvm.addBusyThread("a", 1, 600 * MS);

        assertThat(findingsOf(jvm).get(0).lines.get(0))
                .isEqualTo("1 thread used 50% or more of one core over the last 1.0s");
    }

    @Test
    void listsAtMostFiveThreadsAndCountsThemAll() {
        FakeJvm jvm = jvmWithCpuTime();
        for (int i = 0; i < 7; i++) {
            jvm.addBusyThread("t" + i, 10 + i, (600 + 10 * i) * MS);
        }

        List<Finding> findings = findingsOf(jvm);

        assertThat(findings.get(0).lines).containsExactly(
                "7 threads used 50% or more of one core over the last 1.0s",
                "\"t6\" (0x10) 66% of one core",
                "\"t5\" (0xf) 65% of one core",
                "\"t4\" (0xe) 64% of one core",
                "\"t3\" (0xd) 63% of one core",
                "\"t2\" (0xc) 62% of one core");
        assertThat(findings.get(0).marks).containsExactly("thread:16", "thread:15", "thread:14", "thread:13",
                "thread:12");
    }

    @Test
    void leavesOutAThreadWithoutAWindowReading() {
        FakeJvm jvm = jvmWithCpuTime();
        jvm.addBusyThread("a", 1, 600 * MS);
        jvm.addThread("ended", 2, Thread.State.RUNNABLE);
        when(jvm.threads.getThreadCpuTime(2)).thenReturn(0L, -1L);

        assertThat(findingsOf(jvm).get(0).lines).containsExactly(
                "1 thread used 50% or more of one core over the last 1.0s",
                "\"a\" (0x1) 60% of one core");
    }

    @Test
    void flagsNothingWhenNoThreadIsBusy() {
        FakeJvm jvm = jvmWithCpuTime();
        jvm.addBusyThread("a", 1, 499 * MS);

        assertThat(findingsOf(jvm)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({ "0, true, false", "1, false, false", "1, true, true" })
    void appliesOnlyWithAWindowAndCpuTime(long windowSeconds, boolean cpuTime, boolean applies) {
        FakeJvm jvm = new FakeJvm();
        if (cpuTime) {
            jvm.enableCpuTime();
        }

        assertThat(new HotThreadsCheck().applies(jvm.snapshot(Duration.ofSeconds(windowSeconds)))).isEqualTo(applies);
    }
}
