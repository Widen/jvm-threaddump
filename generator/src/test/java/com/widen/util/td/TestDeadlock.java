package com.widen.util.td;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;

public class TestDeadlock
{

    @Test
    public void testDeadlockThreadDump() throws InterruptedException {
        DeadlockThread.startThreads();
        Thread.sleep(1000);
        JvmThreadDumpImpl out = new JvmThreadDumpImpl();
        String dump = out.generate();
        assertThat(dump, containsString("Memory: used/max"));
        assertThat(dump, not(containsString("Deadlocked Threads: None")));
    }

}
