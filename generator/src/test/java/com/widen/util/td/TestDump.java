package com.widen.util.td;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestDump
{

    @Test
    void printsEverySectionOfTheRunningJvm() {
        assertThat(JvmThreadDump.builder().sampleWindow(Duration.ZERO).build().generate())
                .contains("Current Time: ")
                .contains("JVM Free Memory: ")
                .contains("Memory: used/committed, used/max")
                .contains("GC ")
                .contains("Deadlocked Threads: None")
                .contains("Thread Count: ")
                .contains("\"" + Thread.currentThread().getName() + "\"");
    }

    @Test
    void samplesTheRunningJvm() {
        assertThat(JvmThreadDump.builder().sampleWindow(Duration.ofMillis(50)).build().generate())
                .contains("Thread Count: ");
    }

}
