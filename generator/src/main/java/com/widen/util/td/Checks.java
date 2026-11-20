package com.widen.util.td;

import java.util.Collections;
import java.util.List;

/**
 * The checks the dump runs, in the order the findings block lists them.
 */
final class Checks
{
    static final List<Check> ALL = Collections.unmodifiableList(Collections.<Check>emptyList());

    private Checks() {
    }
}
