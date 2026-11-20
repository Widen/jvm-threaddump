package com.widen.util.td;

import org.junit.jupiter.api.Test;

import static com.widen.util.td.TestText.titled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class TestDeadlockSection
{
    @Test
    void printsNoneWithoutDeadlocks() {
        assertThat(TestText.render(new DeadlockSection(new FakeJvm()).lines()))
                .containsExactly(titled("Deadlocked Threads", "None"));
    }

    @Test
    void printsDeadlockedIdsInHex() {
        FakeJvm jvm = new FakeJvm();
        when(jvm.threads.findDeadlockedThreads()).thenReturn(new long[] { 3, 26 });

        assertThat(TestText.render(new DeadlockSection(jvm).lines()))
                .containsExactly(titled("Deadlocked Threads", "0x3, 0x1a"));
    }
}
