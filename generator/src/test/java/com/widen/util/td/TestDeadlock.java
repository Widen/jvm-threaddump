package com.widen.util.td;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestDeadlock
{

    @Test
    public void testDeadlockThreadDump() throws InterruptedException {
        DeadlockThread.startThreads();
        Thread.sleep(1000);
        JvmThreadDumpImpl out = new JvmThreadDumpImpl();
        String dump = out.generate();
        assertThat(dump).contains("Memory: used/max");
        assertThat(dump).doesNotContain("Deadlocked Threads: None");
    }

}
