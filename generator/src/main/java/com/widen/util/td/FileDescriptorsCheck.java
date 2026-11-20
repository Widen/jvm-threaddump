package com.widen.util.td;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Flags a process that has nearly used up its limit of open file descriptors, which makes new connections and files
 * fail.
 */
final class FileDescriptorsCheck implements Check
{
    static final String TITLE = "File Descriptors";
    static final int MIN_PERCENT = 90;

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return snapshot.openFileDescriptors >= 0 && snapshot.maxFileDescriptors > 0;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        long open = snapshot.openFileDescriptors;
        long max = snapshot.maxFileDescriptors;
        if (open * 100 < MIN_PERCENT * max) {
            return Collections.emptyList();
        }
        String line = String.format(Locale.US, "%,d of %,d file descriptors are open (%d%%)", open, max,
                open * 100 / max);
        return Collections.singletonList(
                new Finding(TITLE, Collections.singletonList(line), Collections.singletonList(Finding.lineMark(TITLE))));
    }
}
