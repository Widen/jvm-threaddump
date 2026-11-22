package com.widen.util.td;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

/**
 * Everything the thread dump reads from the running JVM and its host. Sections read through this interface so tests
 * can supply fixed data.
 */
interface JvmSources
{
    RuntimeMXBean runtime();

    MemoryMXBean memory();

    ThreadMXBean threads();

    List<MemoryPoolMXBean> memoryPools();

    List<GarbageCollectorMXBean> garbageCollectors();

    OperatingSystemMXBean operatingSystem();

    Clock clock();

    TimeZone timeZone();

    /**
     * @return the value, or null when the variable is unset
     */
    String environmentVariable(String name);

    /**
     * @return the value, or null when the property is unset
     */
    String systemProperty(String name);

    /**
     * @return {@link Runtime#freeMemory()}
     */
    long freeMemory();

    /**
     * @return {@link Runtime#maxMemory()}
     */
    long maxMemory();

    /**
     * @return the contents of {@code /proc/uptime}, or empty when the file is unreadable
     */
    Optional<String> procUptime();

    /**
     * @return the keys of {@link Thread#getAllStackTraces()}
     */
    Set<Thread> liveThreads();

    static JvmSources platform() {
        return new PlatformJvmSources();
    }
}
