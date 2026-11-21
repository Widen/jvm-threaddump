package com.widen.util.td;

import java.util.List;

/**
 * A rule that reads a {@link JvmSnapshot} and flags what looks wrong.
 */
interface Check
{
    /**
     * Returns false when the snapshot lacks the data the check needs. The findings block does not count a check that
     * does not apply.
     */
    boolean applies(JvmSnapshot snapshot);

    /**
     * Returns the problems found, or an empty list when nothing is flagged. Called only when {@link #applies} is true.
     */
    List<Finding> findings(JvmSnapshot snapshot);
}
