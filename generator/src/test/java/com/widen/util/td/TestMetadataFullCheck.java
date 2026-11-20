package com.widen.util.td;

import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.widen.util.td.FakeJvm.MB;
import static org.assertj.core.api.Assertions.assertThat;

public class TestMetadataFullCheck
{
    @ParameterizedTest
    @CsvSource({
            "Metaspace,                         1000, 900, true",
            "Metaspace,                         1000, 899, false",
            "Compressed Class Space,            1000, 950, true",
            "Code Cache,                        1000, 950, true",
            "CodeHeap 'profiled nmethods',      1000, 950, true",
            "Metaspace,                           -1, 950, false",
            "G1 Old Gen,                        1000, 950, false" })
    void flagsAMetadataPoolAtNinetyPercent(String name, long maxMb, long usedMb, boolean flagged) {
        FakeJvm jvm = new FakeJvm();
        long max = maxMb < 0 ? -1 : maxMb * MB;
        jvm.addPool(name, MemoryType.NON_HEAP, new MemoryUsage(0, usedMb * MB, Math.max(usedMb * MB, max), max), null);

        List<Finding> findings = new MetadataFullCheck().findings(jvm.snapshot());

        if (flagged) {
            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).title).isEqualTo("Metadata Full");
            assertThat(findings.get(0).lines)
                    .containsExactly(name + " holds " + usedMb * 100 / maxMb + "% of its max");
            assertThat(findings.get(0).marks).containsExactly("pool:" + name);
        }
        else {
            assertThat(findings).isEmpty();
        }
    }
}
