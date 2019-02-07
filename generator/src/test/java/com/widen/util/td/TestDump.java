package com.widen.util.td;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;

public class TestDump
{

    @Test
    public void dumpToStdOut() {
        JvmThreadDumpImpl out = new JvmThreadDumpImpl();
        String dump = out.generate();
        System.out.println(dump);
    }

    @Test
    public void testJvmThreadDump() {
        JvmThreadDumpImpl out = new JvmThreadDumpImpl();
        String dump = out.generate();
        assertThat(dump, containsString("Memory: used/max"));
        assertThat(dump, containsString("Deadlocked Threads: None"));
        assertThat(dump, containsString("file.encoding=UTF-8"));
    }

}
