package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flags a metaspace or code pool whose current usage is nearly its max. A pool with an undefined max is skipped.
 */
final class MetadataFullCheck implements Check
{
    static final String TITLE = "Metadata Full";
    static final int MIN_PERCENT = 90;

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return true;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (PoolSnapshot pool : snapshot.pools) {
            if (isMetadata(pool) && pool.usedPercent() >= MIN_PERCENT) {
                findings.add(new Finding(TITLE,
                        Collections.singletonList(pool.name + " holds " + pool.usedPercent() + "% of its max"),
                        Collections.singletonList(Finding.poolMark(pool.name))));
            }
        }
        return findings;
    }

    private static boolean isMetadata(PoolSnapshot pool) {
        return pool.name.equals("Metaspace") || pool.name.equals("Compressed Class Space")
                || pool.name.equals("Code Cache") || pool.name.equals("CodeCache") || pool.name.startsWith("CodeHeap");
    }
}
