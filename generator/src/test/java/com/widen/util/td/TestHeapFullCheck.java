package com.widen.util.td;

import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.widen.util.td.FakeJvm.MB;
import static org.assertj.core.api.Assertions.assertThat;

public class TestHeapFullCheck
{
    private static MemoryUsage usage(long usedMb, long maxMb) {
        long max = maxMb < 0 ? -1 : maxMb * MB;
        return new MemoryUsage(0, usedMb * MB, Math.max(usedMb * MB, max), max);
    }

    @ParameterizedTest
    @CsvSource({
            "G1 Old Gen,       HEAP,     4000, 3600, 3600, true",
            "PS Old Gen,       HEAP,     4000, 3880, 3840, true",
            "Tenured Gen,      HEAP,     4000, 3880, 3840, true",
            "G1 Old Gen,       HEAP,     4000, 3599, 3800, false",
            "G1 Old Gen,       HEAP,     4000, 3800, 3599, false",
            "G1 Old Gen,       HEAP,     4000, 3800,     , false",
            "G1 Old Gen,       HEAP,     4000, 3800,    0, false",
            "G1 Eden Space,    HEAP,     4000, 3880, 3840, false",
            "Old Things,       NON_HEAP, 4000, 3880, 3840, false",
            "G1 Old Gen,       HEAP,       -1, 3880, 3840, false" })
    void flagsAnOldPoolOnlyWhenBothReadingsAreHigh(String name, MemoryType type, long maxMb, long usedMb,
            Long usedAfterCollectionMb, boolean flagged) {
        FakeJvm jvm = new FakeJvm();
        jvm.addPool(name, type, usage(usedMb, maxMb),
                usedAfterCollectionMb == null ? null : usage(usedAfterCollectionMb, maxMb));

        assertThat(new HeapFullCheck().findings(jvm.snapshot())).hasSize(flagged ? 1 : 0);
    }

    @Test
    void printsBothPercentagesAndTheAllocators() {
        FakeJvm jvm = new FakeJvm();
        jvm.addPool("G1 Old Gen", MemoryType.HEAP, usage(3880, 4000), usage(3840, 4000));
        jvm.enableAllocation();
        jvm.addAllocatingThread("import-7", 0x33, 412 * MB);

        List<Finding> findings = new HeapFullCheck().findings(jvm.snapshot(Duration.ofSeconds(1)));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).title).isEqualTo("Heap Full");
        assertThat(findings.get(0).lines).containsExactly(
                "G1 Old Gen holds 97% of its max, 96% after its last collection",
                "Top allocators: \"import-7\" (0x33) 412 MB/s");
        assertThat(findings.get(0).marks).containsExactly("pool:G1 Old Gen");
    }

    @Test
    void printsOnlyThePercentagesWhenNoWindowRan() {
        FakeJvm jvm = new FakeJvm();
        jvm.addPool("G1 Old Gen", MemoryType.HEAP, usage(3880, 4000), usage(3840, 4000));
        jvm.enableAllocation();
        jvm.addAllocatingThread("import-7", 0x33, 412 * MB);

        assertThat(new HeapFullCheck().findings(jvm.snapshot()).get(0).lines)
                .containsExactly("G1 Old Gen holds 97% of its max, 96% after its last collection");
    }
}
