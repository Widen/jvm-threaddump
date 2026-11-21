package com.widen.util.td;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

public class TestCpuSaturatedCheck
{
    @ParameterizedTest
    @CsvSource({
            "0.90, 0.40, 'system CPU load is 0.90; this JVM uses 0.40 (0.0 to 1.0)'",
            "0.99, 0.05, 'system CPU load is 0.99; this JVM uses 0.05 (0.0 to 1.0)'",
            "0.89, 0.40," })
    void flagsSystemLoadAtNinetyPercent(double system, double process, String expectedLine) {
        FakeJvm jvm = new FakeJvm();
        jvm.cpuLoads(system, process);

        List<Finding> findings = new CpuSaturatedCheck().findings(jvm.snapshot());

        if (expectedLine == null) {
            assertThat(findings).isEmpty();
        }
        else {
            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).title).isEqualTo("CPU Saturated");
            assertThat(findings.get(0).lines).containsExactly(expectedLine);
            assertThat(findings.get(0).marks).containsExactly("line:CPU Load");
        }
    }

    @ParameterizedTest
    @CsvSource({ "-1, 0.40, false", "0.95, -1, false", "0.95, 0.40, true" })
    void appliesOnlyWithBothLoads(double system, double process, boolean applies) {
        FakeJvm jvm = new FakeJvm();
        jvm.cpuLoads(system, process);

        assertThat(new CpuSaturatedCheck().applies(jvm.snapshot())).isEqualTo(applies);
    }
}
