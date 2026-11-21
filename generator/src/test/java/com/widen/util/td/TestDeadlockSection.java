package com.widen.util.td;

import org.junit.jupiter.api.Test;

import static com.widen.util.td.TestText.titled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class TestDeadlockSection
{
    @Test
    void printsNoneWithoutDeadlocks() {
        assertThat(TestText.render(new DeadlockSection(new FakeJvm().snapshot()).lines()))
                .containsExactly(titled("Deadlocked Threads", "None"));
    }

    @Test
    void printsTheCycleThreadsByName() {
        assertThat(TestText.render(new DeadlockSection(TestDeadlockCheck.deadlockOfAAndB().snapshot()).lines()))
                .containsExactly(titled("Deadlocked Threads", "\"a\" (0x1), \"b\" (0x2)"));
    }

    @Test
    void printsTheJvmsThreadsWhenItFindsNoCycle() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("a", 1, Thread.State.RUNNABLE);
        when(jvm.threads.findDeadlockedThreads()).thenReturn(new long[] { 1, 99 });

        assertThat(TestText.render(new DeadlockSection(jvm.snapshot()).lines()))
                .containsExactly(titled("Deadlocked Threads", "\"a\" (0x1), 0x63"));
    }
}
