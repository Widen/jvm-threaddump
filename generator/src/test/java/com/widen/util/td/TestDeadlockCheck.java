package com.widen.util.td;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class TestDeadlockCheck
{
    /** Threads a (1) and b (2) blocked on each other, which the JVM reports. */
    static FakeJvm deadlockOfAAndB() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThreadWaitingOn("a", 1, Thread.State.BLOCKED, 2, "java.lang.Object@1a",
                FakeThreads.frame("com.example.A", "run"));
        jvm.addThreadWaitingOn("b", 2, Thread.State.BLOCKED, 1, "java.lang.Object@2b",
                FakeThreads.frame("com.example.B", "run"));
        when(jvm.threads.findDeadlockedThreads()).thenReturn(new long[] { 1, 2 });
        return jvm;
    }

    private static List<Finding> findingsOf(FakeJvm jvm) {
        return new DeadlockCheck().findings(jvm.snapshot());
    }

    @Test
    void printsTheCycle() {
        List<Finding> findings = findingsOf(deadlockOfAAndB());

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).title).isEqualTo("Deadlock");
        assertThat(findings.get(0).lines).containsExactly(
                "2 threads block each other and will never proceed",
                "\"a\" (0x1) waits for java.lang.Object@1a, held by \"b\"",
                "    com.example.A.run(A.java:1)",
                "\"b\" (0x2) waits for java.lang.Object@2b, held by \"a\"",
                "    com.example.B.run(B.java:1)");
        assertThat(findings.get(0).marks).containsExactly("thread:1", "thread:2");
    }

    @ParameterizedTest
    @CsvSource({ "0,", "1,1 other thread waits behind them", "2,2 other threads wait behind them" })
    void countsTheThreadsBehindTheCycle(int behindCount, String expectedLastLine) {
        FakeJvm jvm = deadlockOfAAndB();
        long ownerId = 1;
        for (int i = 0; i < behindCount; i++) {
            long id = 3 + i;
            jvm.addThreadWaitingOn("c" + id, id, Thread.State.BLOCKED, ownerId, null);
            ownerId = id;
        }

        List<String> lines = findingsOf(jvm).get(0).lines;

        assertThat(lines.subList(5, lines.size()))
                .isEqualTo(expectedLastLine == null ? Collections.emptyList() : Collections.singletonList(expectedLastLine));
    }

    @Test
    void namesAnUnknownLock() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThreadWaitingOn("a", 1, Thread.State.BLOCKED, 2, null);
        jvm.addThreadWaitingOn("b", 2, Thread.State.BLOCKED, 1, null);
        when(jvm.threads.findDeadlockedThreads()).thenReturn(new long[] { 1, 2 });

        assertThat(findingsOf(jvm).get(0).lines)
                .contains("\"a\" (0x1) waits for a lock, held by \"b\"");
    }

    @Test
    void printsOneFindingPerCycle() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThreadWaitingOn("a", 1, Thread.State.BLOCKED, 2, null);
        jvm.addThreadWaitingOn("b", 2, Thread.State.BLOCKED, 1, null);
        jvm.addThreadWaitingOn("c", 3, Thread.State.BLOCKED, 4, null);
        jvm.addThreadWaitingOn("d", 4, Thread.State.BLOCKED, 3, null);
        when(jvm.threads.findDeadlockedThreads()).thenReturn(new long[] { 1, 2, 3, 4 });

        List<Finding> findings = findingsOf(jvm);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).marks).containsExactly("thread:1", "thread:2");
        assertThat(findings.get(1).marks).containsExactly("thread:3", "thread:4");
    }

    @Test
    void findsNothingWithoutAJvmReport() {
        FakeJvm jvm = deadlockOfAAndB();
        when(jvm.threads.findDeadlockedThreads()).thenReturn(null);

        assertThat(findingsOf(jvm)).isEmpty();
    }

    @Test
    void listsTheJvmsThreadsWhenItFindsNoCycle() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("a", 1, Thread.State.RUNNABLE);
        jvm.addThread("b", 2, Thread.State.RUNNABLE);
        when(jvm.threads.findDeadlockedThreads()).thenReturn(new long[] { 1, 2, 99 });

        List<Finding> findings = findingsOf(jvm);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).title).isEqualTo("Deadlock");
        assertThat(findings.get(0).lines).containsExactly(
                "3 threads are deadlocked, by the JVM's report", "\"a\" (0x1)", "\"b\" (0x2)", "thread 0x63");
        assertThat(findings.get(0).marks).containsExactly("thread:1", "thread:2");
    }
}
