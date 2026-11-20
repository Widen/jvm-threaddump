package com.widen.util.td;

import java.lang.management.GarbageCollectorMXBean;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.widen.util.td.FakeJvm.MB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class TestGcOverheadCheck
{
    private static final String LIFETIME_LINE = "GC has taken 10% of JVM uptime, an average since the JVM started";

    /** Adds a collector whose counters read {@code start} when the window opens and {@code end} when it closes. */
    private static void addCollector(FakeJvm jvm, String name, long startCount, long startMillis, long endCount,
            long endMillis) {
        GarbageCollectorMXBean collector = jvm.addCollector(name, startCount, startMillis);
        when(collector.getCollectionCount()).thenReturn(startCount, endCount);
        when(collector.getCollectionTime()).thenReturn(startMillis, endMillis);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 500,  2, 'GC took 50% of the last 1.0s in 2 collections'",
            "1, 300,  1,",
            "4, 1000, 3, 'GC took 25% of the last 4.0s in 3 collections'",
            "4, 999,  3,",
            "2, 500,  1, 'GC took 25% of the last 2.0s in 1 collection'" })
    void flagsGcTimeInTheWindow(long windowSeconds, long gcMillis, long collections, String expectedLine) {
        FakeJvm jvm = new FakeJvm();
        addCollector(jvm, "G1 Young Generation", 10, 1000, 10 + collections, 1000 + gcMillis);

        List<Finding> findings = new GcOverheadCheck().findings(jvm.snapshot(Duration.ofSeconds(windowSeconds)));

        if (expectedLine == null) {
            assertThat(findings).isEmpty();
        }
        else {
            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).title).isEqualTo("GC Overhead");
            assertThat(findings.get(0).lines).containsExactly(expectedLine);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "9006100, false, true",
            "9006100, true,  true",
            "9006099, false, false",
            "9006099, true,  false" })
    void flagsGcTimeOverTheJvmsLifetime(long totalMillis, boolean withWindow, boolean flagged) {
        FakeJvm jvm = new FakeJvm();
        jvm.addCollector("G1 Young Generation", 500, totalMillis);

        List<Finding> findings = new GcOverheadCheck()
                .findings(jvm.snapshot(withWindow ? Duration.ofSeconds(1) : Duration.ZERO));

        if (flagged) {
            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).lines).containsExactly(LIFETIME_LINE);
        }
        else {
            assertThat(findings).isEmpty();
        }
    }

    @Test
    void printsBothMeasuresAndTheAllocators() {
        FakeJvm jvm = new FakeJvm();
        addCollector(jvm, "G1 Young Generation", 10, 9_005_600, 12, 9_006_100);
        jvm.enableAllocation();
        jvm.addAllocatingThread("import-7", 0x33, 412 * MB);

        List<Finding> findings = new GcOverheadCheck().findings(jvm.snapshot(Duration.ofSeconds(1)));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).lines).containsExactly(
                "GC took 50% of the last 1.0s in 2 collections",
                "Top allocators: \"import-7\" (0x33) 412 MB/s",
                LIFETIME_LINE);
    }

    @Test
    void marksTheCollectorsItCounted() {
        FakeJvm windowed = new FakeJvm();
        addCollector(windowed, "Young", 10, 0, 12, 600);
        addCollector(windowed, "Old", 3, 0, 3, 0);
        FakeJvm lifetime = new FakeJvm();
        lifetime.addCollector("Young", 10, 9_006_100);
        lifetime.addCollector("Old", 3, 0);
        lifetime.addCollector("ZGC Cycles", 3, 100_000_000);

        assertThat(new GcOverheadCheck().findings(windowed.snapshot(Duration.ofSeconds(1))).get(0).marks)
                .containsExactly("gc:Young");
        assertThat(new GcOverheadCheck().findings(lifetime.snapshot()).get(0).marks)
                .containsExactly("gc:Young", "gc:Old");
    }

    @Test
    void marksOnlyTheCollectorsThatRanInTheWindowWhenBothMeasuresFlag() {
        FakeJvm jvm = new FakeJvm();
        addCollector(jvm, "Young", 10, 9_005_500, 12, 9_006_100);
        addCollector(jvm, "Old", 3, 0, 3, 0);

        List<Finding> findings = new GcOverheadCheck().findings(jvm.snapshot(Duration.ofSeconds(1)));

        assertThat(findings.get(0).lines).containsExactly(
                "GC took 60% of the last 1.0s in 2 collections", LIFETIME_LINE);
        assertThat(findings.get(0).marks).containsExactly("gc:Young");
    }

    @Test
    void leavesOutConcurrentCollectorsAndUnknownTimes() {
        FakeJvm jvm = new FakeJvm();
        addCollector(jvm, "G1 Young Generation", 10, 1000, 12, 1500);
        addCollector(jvm, "G1 Concurrent GC", 0, 0, 5, 900);
        addCollector(jvm, "ZGC Cycles", 0, 0, 3, 900);
        addCollector(jvm, "Undefined", -1, -1, -1, -1);
        jvm.addCollector("Huge Concurrent Cycles", 1, 100_000_000);

        List<Finding> findings = new GcOverheadCheck().findings(jvm.snapshot(Duration.ofSeconds(1)));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).lines).containsExactly("GC took 50% of the last 1.0s in 2 collections");
        assertThat(findings.get(0).marks).containsExactly("gc:G1 Young Generation");
    }
}
