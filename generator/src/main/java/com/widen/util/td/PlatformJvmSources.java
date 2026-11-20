package com.widen.util.td;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

final class PlatformJvmSources implements JvmSources
{
    @Override
    public RuntimeMXBean runtime() {
        return ManagementFactory.getRuntimeMXBean();
    }

    @Override
    public MemoryMXBean memory() {
        return ManagementFactory.getMemoryMXBean();
    }

    @Override
    public ThreadMXBean threads() {
        return ManagementFactory.getThreadMXBean();
    }

    @Override
    public List<MemoryPoolMXBean> memoryPools() {
        return ManagementFactory.getMemoryPoolMXBeans();
    }

    @Override
    public List<GarbageCollectorMXBean> garbageCollectors() {
        return ManagementFactory.getGarbageCollectorMXBeans();
    }

    @Override
    public OperatingSystemMXBean operatingSystem() {
        return ManagementFactory.getOperatingSystemMXBean();
    }

    @Override
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Override
    public TimeZone timeZone() {
        return TimeZone.getDefault();
    }

    @Override
    public String environmentVariable(String name) {
        return System.getenv(name);
    }

    @Override
    public String systemProperty(String name) {
        return System.getProperty(name);
    }

    @Override
    public long freeMemory() {
        return Runtime.getRuntime().freeMemory();
    }

    @Override
    public long maxMemory() {
        return Runtime.getRuntime().maxMemory();
    }

    @Override
    public Optional<String> procUptime() {
        try {
            return Optional.of(new String(Files.readAllBytes(Paths.get("/proc/uptime")), StandardCharsets.US_ASCII));
        }
        catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Set<Thread> liveThreads() {
        return Thread.getAllStackTraces().keySet();
    }

    @Override
    public void pause(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis(), duration.getNano() % 1_000_000);
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
