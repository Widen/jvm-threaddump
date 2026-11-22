package com.widen.util.td;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The CPU limit of the container and the kernel's count of the periods it was throttled in, read from the cgroup files
 * where a container runtime mounts the container's own cgroup.
 */
final class CgroupCpu
{
    private static final String V2_DIRECTORY = "/sys/fs/cgroup/";
    private static final List<String> V1_DIRECTORIES = Arrays.asList(
            "/sys/fs/cgroup/cpu/", "/sys/fs/cgroup/cpu,cpuacct/");

    /** The quota divided by the period, above zero. */
    final double limitCpus;
    /** The length of one period in microseconds, above zero. */
    final long periodMicros;
    /** {@code nr_periods}, which counts only the periods in which the container had work to run. */
    final long periods;
    /** {@code nr_throttled} */
    final long throttledPeriods;

    private CgroupCpu(double limitCpus, long periodMicros, long periods, long throttledPeriods) {
        this.limitCpus = limitCpus;
        this.periodMicros = periodMicros;
        this.periods = periods;
        this.throttledPeriods = throttledPeriods;
    }

    /**
     * Reads cgroup v2, then v1. The first directory whose limit file is readable decides.
     *
     * @return null when the container has no limit or a file does not parse
     */
    static CgroupCpu read(JvmSources jvm) {
        Optional<String> max = jvm.file(V2_DIRECTORY + "cpu.max");
        if (max.isPresent()) {
            String[] fields = max.get().trim().split("\\s+");
            // A quota of "max", meaning no limit, fails to parse like any other text.
            return fields.length == 2 ? read(jvm, V2_DIRECTORY, fields[0], fields[1]) : null;
        }
        for (String directory : V1_DIRECTORIES) {
            Optional<String> quota = jvm.file(directory + "cpu.cfs_quota_us");
            if (quota.isPresent()) {
                return read(jvm, directory, quota.get(), jvm.file(directory + "cpu.cfs_period_us").orElse(""));
            }
        }
        return null;
    }

    private static CgroupCpu read(JvmSources jvm, String directory, String quotaText, String periodText) {
        try {
            long quota = Long.parseLong(quotaText.trim());
            long period = Long.parseLong(periodText.trim());
            if (quota <= 0 || period <= 0) {
                return null;
            }
            String stat = jvm.file(directory + "cpu.stat").orElse("");
            long periods = counter(stat, "nr_periods");
            long throttledPeriods = counter(stat, "nr_throttled");
            if (periods < 0 || throttledPeriods < 0) {
                return null;
            }
            return new CgroupCpu((double) quota / period, period, periods, throttledPeriods);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @return the number on the line that starts with the name, or -1 when no line does
     */
    private static long counter(String stat, String name) {
        String prefix = name + " ";
        for (String line : stat.split("\n")) {
            if (line.startsWith(prefix)) {
                return Long.parseLong(line.substring(prefix.length()).trim());
            }
        }
        return -1;
    }
}
