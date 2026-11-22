package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One problem a {@link Check} found. The marks name the lines of the dump to flag with the finding's title.
 */
final class Finding
{
    final String title;
    /** At least one entry; the first prints beside the title and the rest under it. */
    final List<String> lines;
    final Set<String> marks;

    Finding(String title, List<String> lines, Collection<String> marks) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a finding needs at least one line: " + title);
        }
        this.title = title;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.marks = Collections.unmodifiableSet(new LinkedHashSet<>(marks));
    }

    static String threadMark(long id) {
        return "thread:" + id;
    }

    static String poolMark(String name) {
        return "pool:" + name;
    }

    static String gcMark(String name) {
        return "gc:" + name;
    }

    static String lineMark(String title) {
        return "line:" + title;
    }
}
