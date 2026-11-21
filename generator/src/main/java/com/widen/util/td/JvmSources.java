package com.widen.util.td;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Clock;
import java.time.Duration;
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
     * @return the file's text, or empty when the file is missing or unreadable
     */
    Optional<String> file(String path);

    /**
     * @return every live platform thread, read without walking the stacks
     */
    Set<Thread> liveThreads();

    /**
     * Blocks the calling thread for the duration.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void pause(Duration duration) throws InterruptedException;

    /**
     * @return a monotonic reading in nanoseconds, as {@link System#nanoTime()} gives
     */
    long nanoTime();

    static JvmSources platform() {
        return new PlatformJvmSources();
    }
}
