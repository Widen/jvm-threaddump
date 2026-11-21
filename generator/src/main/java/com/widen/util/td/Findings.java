package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The result of running the checks once, and the markers that flag lines of the dump.
 */
final class Findings
{
    static final Findings NONE = new Findings(0, 0, Collections.emptyList());

    /** The checks that applied. */
    final int checksRun;
    /** The applied checks that returned at least one finding. */
    final int checksFlagged;
    /** In check order, then in the order each check returned them. */
    final List<Finding> findings;

    private Findings(int checksRun, int checksFlagged, List<Finding> findings) {
        this.checksRun = checksRun;
        this.checksFlagged = checksFlagged;
        this.findings = findings;
    }

    static Findings run(JvmSnapshot snapshot, List<Check> checks) {
        int checksRun = 0;
        int checksFlagged = 0;
        List<Finding> findings = new ArrayList<>();
        for (Check check : checks) {
            if (!check.applies(snapshot)) {
                continue;
            }
            checksRun++;
            List<Finding> found = check.findings(snapshot);
            if (!found.isEmpty()) {
                checksFlagged++;
                findings.addAll(found);
            }
        }
        return new Findings(checksRun, checksFlagged, Collections.unmodifiableList(findings));
    }

    /**
     * Returns {@code " <<< "} and the distinct titles of the findings that carry the mark, or an empty string when
     * none does.
     */
    String marker(String mark) {
        Set<String> titles = new LinkedHashSet<>();
        for (Finding finding : findings) {
            if (finding.marks.contains(mark)) {
                titles.add(finding.title);
            }
        }
        return titles.isEmpty() ? "" : " <<< " + String.join(", ", titles);
    }
}
