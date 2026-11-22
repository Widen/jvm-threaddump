package com.widen.util.td;

import java.util.Arrays;
import java.util.List;
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
            String dump = JvmThreadDump.create().generate();
            String[] fixtureIds = Arrays.stream(deadlock.threadIds())
                    .mapToObj(id -> "0x" + Long.toHexString(id))
                    .toArray(String[]::new);
            assertThat(deadlockedThreadIds(dump)).contains(fixtureIds);
        }
        assertThat(JvmThreadDump.create().generate())
                .contains("Deadlocked Threads: None");
    }

    private static List<String> deadlockedThreadIds(String dump) {
        Matcher line = DEADLOCKED_LINE.matcher(dump);
        assertThat(line.find()).as("a Deadlocked Threads line in the dump").isTrue();
        return Arrays.asList(line.group(1).trim().split(", "));
    }

}
