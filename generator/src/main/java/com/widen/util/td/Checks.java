package com.widen.util.td;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The checks the dump runs, in the order the findings block lists them.
 */
final class Checks
{
    private Checks() {
    }

    /**
     * @param idleLibraryPrefixes the package prefixes {@link StackPileUpCheck} adds to its idle rule
     */
    static List<Check> all(List<String> idleLibraryPrefixes) {
        return Collections.unmodifiableList(Arrays.<Check>asList(
                new DeadlockCheck(),
                new LockPileUpCheck(),
                new StackPileUpCheck(idleLibraryPrefixes),
                new HeapFullCheck(),
                new MetadataFullCheck(),
                new GcOverheadCheck(),
                new CpuSaturatedCheck(),
                new CpuThrottledCheck(),
                new FileDescriptorsCheck(),
                new HotThreadsCheck()));
    }
}
