package com.widen.util.td;

import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flags an old-generation pool that is nearly full both now and after its last collection. Either reading alone
 * misleads: current usage includes garbage, and the after-collection reading can be hours old. A heap pool is old
 * generation when its name contains {@code Old} or {@code Tenured}, or when it is the JVM's only heap pool, as with
 * Shenandoah and non-generational ZGC.
 */
final class HeapFullCheck implements Check
{
    static final String TITLE = "Heap Full";
    static final int MIN_PERCENT = 90;

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return true;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        int heapPools = 0;
        for (PoolSnapshot pool : snapshot.pools) {
            if (pool.type == MemoryType.HEAP) {
                heapPools++;
            }
        }

        List<Finding> findings = new ArrayList<>();
        for (PoolSnapshot pool : snapshot.pools) {
            if (isOldGeneration(pool, heapPools) && pool.usedPercent() >= MIN_PERCENT
                    && pool.collectionUsedPercent() >= MIN_PERCENT) {
                List<String> lines = new ArrayList<>();
                lines.add(pool.name + " holds " + pool.usedPercent() + "% of its max, "
                        + pool.collectionUsedPercent() + "% after its last collection");
                lines.addAll(Allocators.lines(snapshot));
                findings.add(new Finding(TITLE, lines, Collections.singletonList(Finding.poolMark(pool.name))));
            }
        }
        return findings;
    }

    private static boolean isOldGeneration(PoolSnapshot pool, int heapPools) {
        return pool.type == MemoryType.HEAP
                && (heapPools == 1 || pool.name.contains("Old") || pool.name.contains("Tenured"));
    }
}
