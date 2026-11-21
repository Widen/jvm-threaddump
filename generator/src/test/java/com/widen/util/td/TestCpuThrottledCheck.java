package com.widen.util.td;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class TestCpuThrottledCheck
{
    private static final String LIMIT_LINE = "the limit is 1.00 CPUs";

    @ParameterizedTest
    @CsvSource({
            "1000, 3, 10, true,  'the kernel throttled this container in 3 periods (30%) of the last 1.0s'",
            "1000, 2, 10, false,",
            "1000, 3, 3,  true,  'the kernel throttled this container in 3 periods (30%) of the last 1.0s'",
            "1000, 2, 2,  false,",
            "1000, 0, 10, false,",
            "200,  1, 1,  false,",
            "200,  2, 2,  true,  'the kernel throttled this container in 2 periods (100%) of the last 0.2s'",
            "100,  2, 2,  true,  'the kernel throttled this container in 2 periods (100%) of the last 0.1s'" })
    void flagsAThrottledWindow(long windowMillis, long throttled, long periods, boolean flagged, String expectedLine) {
        FakeJvm jvm = new FakeJvm();
        jvm.cgroupV2(100_000, 1_000, 0);
        FakeJvm later = new FakeJvm();
        later.cgroupV2(100_000, 1_000 + periods, throttled);
        jvm.filesAfterPause.putAll(later.files);

        List<Finding> findings = new CpuThrottledCheck().findings(jvm.snapshot(Duration.ofMillis(windowMillis)));

        if (flagged) {
            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).lines).containsExactly(expectedLine, LIMIT_LINE);
        }
        else {
            assertThat(findings).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "300, 86400, 300000,  true",
            "299, 86400, 300000,  false",
            "300, 86400, 299999,  false",
            "300, 600,   3600000, false",
            "0,   0,     300000,  false" })
    void flagsALifetimeOfThrottling(long throttled, long periods, long uptimeMillis, boolean flagged) {
        FakeJvm jvm = new FakeJvm();
        jvm.cgroupV2(100_000, periods, throttled);
        when(jvm.runtime.getUptime()).thenReturn(uptimeMillis);

        List<Finding> findings = new CpuThrottledCheck().findings(jvm.snapshot());

        if (flagged) {
            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).lines).containsExactly(
                    "the kernel has throttled this container for 10% of its uptime", LIMIT_LINE);
        }
        else {
            assertThat(findings).isEmpty();
        }
    }

    @Test
    void printsEachMeasureThatFlaggedThenTheLimit() {
        FakeJvm jvm = new FakeJvm();
        jvm.cgroupV2(100_000, 86_358, 10_344);
        FakeJvm later = new FakeJvm();
        later.cgroupV2(100_000, 86_400, 10_365);
        jvm.filesAfterPause.putAll(later.files);
        when(jvm.runtime.getUptime()).thenReturn(6_000_000L);

        List<Finding> findings = new CpuThrottledCheck().findings(jvm.snapshot(Duration.ofMillis(4100)));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).title).isEqualTo("CPU Throttled");
        assertThat(findings.get(0).lines).containsExactly(
                "the kernel throttled this container in 21 periods (51%) of the last 4.1s",
                "the kernel has throttled this container for 17% of its uptime",
                "the limit is 1.00 CPUs");
        assertThat(findings.get(0).marks).containsExactly("line:CPU Limit");
    }

    @Test
    void countsEachThrottledPeriodAtTheContainersPeriodLength() {
        FakeJvm jvm = new FakeJvm();
        jvm.files.put("/sys/fs/cgroup/cpu.max", "25000 50000\n");
        jvm.files.put("/sys/fs/cgroup/cpu.stat", "nr_periods 5000\nnr_throttled 595\n");
        jvm.filesAfterPause.put("/sys/fs/cgroup/cpu.stat", "nr_periods 5020\nnr_throttled 600\n");
        when(jvm.runtime.getUptime()).thenReturn(300_000L);

        List<Finding> findings = new CpuThrottledCheck().findings(jvm.snapshot(Duration.ofSeconds(1)));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).lines).containsExactly(
                "the kernel throttled this container in 5 periods (25%) of the last 1.0s",
                "the kernel has throttled this container for 10% of its uptime",
                "the limit is 0.50 CPUs");
    }

    @Test
    void appliesOnlyWithACpuLimit() {
        FakeJvm limited = new FakeJvm();
        limited.cgroupV2(100_000, 100, 0);

        assertThat(new CpuThrottledCheck().applies(limited.snapshot())).isTrue();
        assertThat(new CpuThrottledCheck().applies(new FakeJvm().snapshot())).isFalse();
    }
}
