package com.widen.util.td;

import java.util.List;

import org.junit.jupiter.api.Test;

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
    void printsTheCycleAndCountsTheThreadsBehindIt() {
        FakeJvm jvm = deadlockOfAAndB();
        jvm.addThreadWaitingOn("c", 3, Thread.State.BLOCKED, 1, null);
        when(jvm.threads.findDeadlockedThreads()).thenReturn(new long[] { 3, 1, 2 });

        List<Finding> findings = findingsOf(jvm);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).title).isEqualTo("Deadlock");
        assertThat(findings.get(0).lines).containsExactly(
                "2 threads block each other and will never proceed",
                "\"a\" (0x1) waits for java.lang.Object@1a, held by \"b\"",
                "    com.example.A.run(A.java:1)",
                "\"b\" (0x2) waits for java.lang.Object@2b, held by \"a\"",
                "    com.example.B.run(B.java:1)",
                "1 other thread waits behind them");
        assertThat(findings.get(0).marks).containsExactly("thread:1", "thread:2");
    }

    @Test
    void countsSeveralThreadsBehindTheCycle() {
        FakeJvm jvm = deadlockOfAAndB();
        jvm.addThreadWaitingOn("c", 3, Thread.State.BLOCKED, 1, null);
        jvm.addThreadWaitingOn("d", 4, Thread.State.BLOCKED, 3, null);

        assertThat(findingsOf(jvm).get(0).lines).endsWith("2 other threads wait behind them");
    }

    @Test
    void leavesOutTheBehindLineWhenNobodyWaits() {
        assertThat(findingsOf(deadlockOfAAndB()).get(0).lines).hasSize(5).noneMatch(line -> line.contains("behind"));
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
