package com.widen.util.td;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;

public class TestDump
{

    @Test
    public void dumpToStdOut() {
        JvmThreadDump out = new JvmThreadDump();
        String dump = out.generate();
        System.out.println(dump);
    }

    @Test
    public void testJvmThreadDump() {
        JvmThreadDump out = new JvmThreadDump();
        String dump = out.generate();
        assertThat(dump, containsString("Memory: used/max"));
        assertThat(dump, containsString("Deadlocked Threads: None"));
    }

}
