package com.widen.util.td;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(dump).contains("Memory: used/max");
        assertThat(dump).contains("Deadlocked Threads: None");
        assertThat(dump).contains("file.encoding=UTF-8");
    }

}
