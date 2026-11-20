package com.widen.util.td;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class TestLockPileUpCheck
{
    private static final StackTraceElement[] HOLDER_STACK = {
            FakeThreads.frame("com.example.H", "work"),
            FakeThreads.frame("$H_1a", "work"),
            FakeThreads.frame("com.example.H", "run"),
            FakeThreads.frame("java.lang.Thread", "run"),
            FakeThreads.frame("com.example.Main", "main") };

    /** Adds {@code count} threads, blocked on a lock that {@code holderId} holds, with ids from {@code holderId * 100}. */
    static void addWaiters(FakeJvm jvm, int count, long holderId) {
        for (int i = 0; i < count; i++) {
            jvm.addThreadWaitingOn("w" + holderId + "-" + i, holderId * 100 + i, Thread.State.BLOCKED, holderId, null);
        }
    }

    private static List<Finding> findingsOf(FakeJvm jvm) {
        return new LockPileUpCheck().findings(jvm.snapshot());
    }

    @ParameterizedTest
    @CsvSource({ "4,0", "5,1" })
    void flagsAHolderWithFiveOrMoreThreadsBehindIt(int waiters, int expectedFindings) {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("h", 10, Thread.State.RUNNABLE);
        addWaiters(jvm, waiters, 10);

        assertThat(findingsOf(jvm)).hasSize(expectedFindings);
    }

    @Test
    void printsTheHolderAndItsFrames() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("h", 10, Thread.State.RUNNABLE, HOLDER_STACK);
        addWaiters(jvm, 6, 10);

        List<Finding> findings = findingsOf(jvm);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).title).isEqualTo("Lock Pile-up");
        assertThat(findings.get(0).lines).containsExactly(
                "6 threads wait on a lock held by \"h\" (0xa, RUNNABLE)",
                "    com.example.H.work(H.java:1)",
                "    com.example.H.run(H.java:1)",
                "    java.lang.Thread.run(Thread.java:1)");
        assertThat(findings.get(0).marks).containsExactly("thread:10");
    }

    @Test
    void namesAMissingHolderById() {
        FakeJvm jvm = new FakeJvm();
        addWaiters(jvm, 5, 99);

        List<Finding> findings = findingsOf(jvm);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).lines)
                .containsExactly("5 threads wait on a lock held by thread 0x63, which is not in the dump");
        assertThat(findings.get(0).marks).isEmpty();
    }

    @Test
    void printsTheLargestPileUpFirst() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("a-holder", 10, Thread.State.RUNNABLE);
        jvm.addThread("b-holder", 20, Thread.State.RUNNABLE);
        addWaiters(jvm, 5, 10);
        addWaiters(jvm, 7, 20);

        assertThat(findingsOf(jvm)).extracting(finding -> finding.lines.get(0)).containsExactly(
                "7 threads wait on a lock held by \"b-holder\" (0x14, RUNNABLE)",
                "5 threads wait on a lock held by \"a-holder\" (0xa, RUNNABLE)");
    }

    @Test
    void leavesDeadlocksToTheDeadlockCheck() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThreadWaitingOn("a", 1, Thread.State.BLOCKED, 2, null);
        jvm.addThreadWaitingOn("b", 2, Thread.State.BLOCKED, 1, null);
        addWaiters(jvm, 6, 1);
        when(jvm.threads.findDeadlockedThreads()).thenReturn(new long[] { 1, 2 });

        assertThat(findingsOf(jvm)).isEmpty();
    }
}
