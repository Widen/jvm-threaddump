package com.widen.util.td;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestDeadlock
{
    private static final Pattern DEADLOCKED_LINE = Pattern.compile("^ *Deadlocked Threads: (.*)$", Pattern.MULTILINE);

    @Test
    void reportsDeadlockedThreadsAndClearsThemAfterward() throws Exception {
        try (DeadlockFixture deadlock = DeadlockFixture.start()) {
            String dump = withoutSampling().generate();
            assertThat(dump).contains("Deadlock: 2 threads block each other");
            assertThat(deadlockedThreadsLine(dump))
                    .contains("\"deadlock-fixture-1\" (0x" + Long.toHexString(deadlock.threadIds()[0]) + ")")
                    .contains("\"deadlock-fixture-2\" (0x" + Long.toHexString(deadlock.threadIds()[1]) + ")");
        }
        assertThat(withoutSampling().generate())
                .contains("Deadlocked Threads: None");
    }

    private static JvmThreadDump withoutSampling() {
        return JvmThreadDump.builder().sampleWindow(Duration.ZERO).build();
    }

    private static String deadlockedThreadsLine(String dump) {
        Matcher line = DEADLOCKED_LINE.matcher(dump);
        assertThat(line.find()).as("a Deadlocked Threads line in the dump").isTrue();
        return line.group(1);
    }

}
