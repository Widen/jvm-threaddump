package com.widen.util.td;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestLockGraph
{
    private static final StackTraceElement PARK = FakeThreads.frame("sun.misc.Unsafe", "park");

    @Test
    void findsACycleAndTheThreadsBehindIt() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThreadWaitingOn("a", 1, Thread.State.BLOCKED, 2, null);
        jvm.addThreadWaitingOn("b", 2, Thread.State.BLOCKED, 1, null);
        jvm.addThreadWaitingOn("c", 3, Thread.State.BLOCKED, 1, null);
        jvm.addThreadWaitingOn("d", 4, Thread.State.BLOCKED, 3, null);
        jvm.addThread("e", 5, Thread.State.RUNNABLE);
        JvmSnapshot snapshot = jvm.snapshot();
        LockGraph graph = LockGraph.of(snapshot.threads);

        assertThat(graph.cycles()).containsExactly(Arrays.asList(thread(snapshot, 1), thread(snapshot, 2)));
        assertThat(graph.behind(graph.cycles().get(0))).containsExactly(thread(snapshot, 3), thread(snapshot, 4));
        assertThat(graph.inOrBehindCycles()).isEqualTo(new HashSet<>(Arrays.asList(1L, 2L, 3L, 4L)));
        assertThat(graph.waitersByHolder()).isEmpty();
    }

    @Test
    void findsACycleWhenAThreadBehindItComesFirst() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThreadWaitingOn("c", 1, Thread.State.BLOCKED, 2, null);
        jvm.addThreadWaitingOn("d", 2, Thread.State.BLOCKED, 3, null);
        jvm.addThreadWaitingOn("e", 3, Thread.State.BLOCKED, 2, null);
        JvmSnapshot snapshot = jvm.snapshot();
        LockGraph graph = LockGraph.of(snapshot.threads);

        assertThat(graph.cycles()).containsExactly(Arrays.asList(thread(snapshot, 2), thread(snapshot, 3)));
        assertThat(graph.behind(graph.cycles().get(0))).containsExactly(thread(snapshot, 1));
    }

    @Test
    void ignoresAThreadBlockedOnItself() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThreadWaitingOn("s", 5, Thread.State.BLOCKED, 5, null);
        LockGraph graph = LockGraph.of(jvm.snapshot().threads);

        assertThat(graph.cycles()).isEmpty();
        assertThat(graph.waitersByHolder()).isEmpty();
    }

    @Test
    void followsAChainToItsHolder() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("h", 10, Thread.State.RUNNABLE);
        jvm.addThreadWaitingOn("w1", 11, Thread.State.BLOCKED, 10, null);
        jvm.addThreadWaitingOn("m", 12, Thread.State.WAITING, 10, null, PARK);
        jvm.addThreadWaitingOn("w2", 13, Thread.State.BLOCKED, 12, null);
        JvmSnapshot snapshot = jvm.snapshot();

        assertThat(LockGraph.of(snapshot.threads).waitersByHolder()).hasSize(1).containsEntry(10L,
                Arrays.asList(thread(snapshot, 12), thread(snapshot, 11), thread(snapshot, 13)));
    }

    @Test
    void ignoresAThreadInsideObjectWait() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("h", 10, Thread.State.RUNNABLE);
        jvm.addThreadWaitingOn("p", 20, Thread.State.WAITING, 10, null,
                FakeThreads.frame("java.lang.Object", "wait"));

        assertThat(LockGraph.of(jvm.snapshot().threads).waitersByHolder()).isEmpty();
    }

    @Test
    void endsAChainAtAnOwnerMissingFromTheSnapshot() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThreadWaitingOn("w", 30, Thread.State.BLOCKED, 99, null);
        JvmSnapshot snapshot = jvm.snapshot();

        assertThat(LockGraph.of(snapshot.threads).waitersByHolder())
                .isEqualTo(Collections.singletonMap(99L, Collections.singletonList(thread(snapshot, 30))));
    }

    private static ThreadSnapshot thread(JvmSnapshot snapshot, long id) {
        return snapshot.thread(id);
    }
}
