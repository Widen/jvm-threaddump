package com.widen.util.td;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestDeadlock
{

    @Test
    void reportsDeadlockedThreadsAndClearsThemAfterward() throws Exception {
        try (DeadlockFixture deadlock = DeadlockFixture.start()) {
            String dump = new JvmThreadDumpImpl().generate();
            assertThat(dump).doesNotContain("Deadlocked Threads: None");
            for (long id : deadlock.threadIds()) {
                assertThat(dump).contains("0x" + Long.toHexString(id));
            }
        }
        assertThat(new JvmThreadDumpImpl().generate())
                .contains("Deadlocked Threads: None");
    }

}
