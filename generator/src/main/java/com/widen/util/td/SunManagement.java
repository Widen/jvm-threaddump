package com.widen.util.td;

import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;

/**
 * Narrows the platform beans to their {@code com.sun.management} types, which add the allocation, CPU load, physical
 * memory, and file descriptor figures. A runtime image built without the {@code jdk.management} module has none of
 * those types, and an {@code instanceof} that names one throws {@link NoClassDefFoundError} there, so every such
 * test goes through this class.
 */
final class SunManagement
{
    private static final boolean PRESENT = present();

    private SunManagement() {
    }

    /** Returns the bean as a {@code com.sun.management} bean, or null when it is not one. */
    static com.sun.management.ThreadMXBean threads(ThreadMXBean bean) {
        return PRESENT && bean instanceof com.sun.management.ThreadMXBean
                ? (com.sun.management.ThreadMXBean) bean : null;
    }

    /** Returns the bean as a {@code com.sun.management} bean, or null when it is not one. */
    static com.sun.management.OperatingSystemMXBean operatingSystem(OperatingSystemMXBean bean) {
        return PRESENT && bean instanceof com.sun.management.OperatingSystemMXBean
                ? (com.sun.management.OperatingSystemMXBean) bean : null;
    }

    /** Returns the bean as a Unix bean, or null when it is not one. */
    static com.sun.management.UnixOperatingSystemMXBean unixOperatingSystem(OperatingSystemMXBean bean) {
        return PRESENT && bean instanceof com.sun.management.UnixOperatingSystemMXBean
                ? (com.sun.management.UnixOperatingSystemMXBean) bean : null;
    }

    private static boolean present() {
        try {
            Class.forName("com.sun.management.OperatingSystemMXBean");
            return true;
        }
        catch (ClassNotFoundException e) {
            return false;
        }
    }
}
