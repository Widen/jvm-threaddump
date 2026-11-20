package com.widen.util.td;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestDump
{

    @Test
    void printsEverySectionOfTheRunningJvm() {
        assertThat(JvmThreadDump.create().generate())
                .contains("Current Time: ")
                .contains("JVM Free Memory: ")
                .contains("Memory: used/committed, used/max")
                .contains("GC ")
                .contains("Deadlocked Threads: None")
                .contains("Thread Count: ")
                .contains("\"Test worker\"");
    }

}
