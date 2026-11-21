package com.widen.util.td;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class TestCgroupCpu
{
    private static final String V2_STAT = "usage_usec 2844155\nuser_usec 2668749\nsystem_usec 175406\n"
            + "nice_usec 0\nnr_periods 42\nnr_throttled 21\nthrottled_usec 2789108\nnr_bursts 0\nburst_usec 0\n";
    private static final String V1_STAT = "nr_periods 42\nnr_throttled 21\nthrottled_time 2789108000\n";

    static Stream<Arguments> readsTheLimitAndCounters() {
        return Stream.of(
                arguments("v2", files("/sys/fs/cgroup/cpu.max", "100000 100000\n", "/sys/fs/cgroup/cpu.stat", V2_STAT), 1.0),
                arguments("v2 half a CPU", files("/sys/fs/cgroup/cpu.max", "50000 100000\n", "/sys/fs/cgroup/cpu.stat", V2_STAT), 0.5),
                arguments("v1", files("/sys/fs/cgroup/cpu/cpu.cfs_quota_us", "200000\n",
                        "/sys/fs/cgroup/cpu/cpu.cfs_period_us", "100000\n", "/sys/fs/cgroup/cpu/cpu.stat", V1_STAT), 2.0),
                arguments("v1 under cpu,cpuacct", files("/sys/fs/cgroup/cpu,cpuacct/cpu.cfs_quota_us", "150000\n",
                        "/sys/fs/cgroup/cpu,cpuacct/cpu.cfs_period_us", "100000\n",
                        "/sys/fs/cgroup/cpu,cpuacct/cpu.stat", V1_STAT), 1.5));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void readsTheLimitAndCounters(String description, Map<String, String> files, double limitCpus) {
        FakeJvm jvm = new FakeJvm();
        jvm.files.putAll(files);

        CgroupCpu cpu = CgroupCpu.read(jvm);

        assertThat(cpu.limitCpus).isEqualTo(limitCpus);
        assertThat(cpu.periodMicros).isEqualTo(100_000);
        assertThat(cpu.periods).isEqualTo(42);
        assertThat(cpu.throttledPeriods).isEqualTo(21);
    }

    static Stream<Arguments> hasNoLimit() {
        return Stream.of(
                arguments("no cgroup files", files()),
                arguments("v2 without a quota", files("/sys/fs/cgroup/cpu.max", "max 100000\n", "/sys/fs/cgroup/cpu.stat", V2_STAT)),
                arguments("v2 without a quota, v1 files also present", files(
                        "/sys/fs/cgroup/cpu.max", "max 100000\n", "/sys/fs/cgroup/cpu.stat", V2_STAT,
                        "/sys/fs/cgroup/cpu/cpu.cfs_quota_us", "200000\n", "/sys/fs/cgroup/cpu/cpu.cfs_period_us", "100000\n",
                        "/sys/fs/cgroup/cpu/cpu.stat", V1_STAT)),
                arguments("v1 without a quota", files("/sys/fs/cgroup/cpu/cpu.cfs_quota_us", "-1\n",
                        "/sys/fs/cgroup/cpu/cpu.cfs_period_us", "100000\n", "/sys/fs/cgroup/cpu/cpu.stat", V1_STAT)),
                arguments("v2 with a zero period", files("/sys/fs/cgroup/cpu.max", "100000 0\n")),
                arguments("v2 with one field", files("/sys/fs/cgroup/cpu.max", "100000\n")),
                arguments("v2 with text for a quota", files("/sys/fs/cgroup/cpu.max", "lots 100000\n")),
                arguments("v2 without cpu.stat", files("/sys/fs/cgroup/cpu.max", "100000 100000\n")),
                arguments("v2 with a counter missing", files("/sys/fs/cgroup/cpu.max", "100000 100000\n",
                        "/sys/fs/cgroup/cpu.stat", "nr_periods 42\n")),
                arguments("v2 with text for a counter", files("/sys/fs/cgroup/cpu.max", "100000 100000\n",
                        "/sys/fs/cgroup/cpu.stat", "nr_periods many\nnr_throttled 21\n")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void hasNoLimit(String description, Map<String, String> files) {
        FakeJvm jvm = new FakeJvm();
        jvm.files.putAll(files);

        assertThat(CgroupCpu.read(jvm)).isNull();
    }

    private static Map<String, String> files(String... pathsAndTexts) {
        Map<String, String> files = new HashMap<>();
        for (int i = 0; i < pathsAndTexts.length; i += 2) {
            files.put(pathsAndTexts[i], pathsAndTexts[i + 1]);
        }
        return files;
    }
}
