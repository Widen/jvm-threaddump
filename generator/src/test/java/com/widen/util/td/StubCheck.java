package com.widen.util.td;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A {@link Check} with a fixed answer.
 */
final class StubCheck implements Check
{
    private final boolean applies;
    private final List<Finding> findings;

    private StubCheck(boolean applies, List<Finding> findings) {
        this.applies = applies;
        this.findings = findings;
    }

    static StubCheck flagging(Finding... findings) {
        return new StubCheck(true, Arrays.asList(findings));
    }

    static StubCheck notApplying() {
        return new StubCheck(false, Collections.emptyList());
    }

    /**
     * The result of running one check that returns the given findings.
     */
    static Findings findingsOf(JvmSnapshot snapshot, Finding... findings) {
        return Findings.run(snapshot, Collections.singletonList(flagging(findings)));
    }

    static Finding finding(String title, String... marks) {
        return new Finding(title, Collections.singletonList("detail"), Arrays.asList(marks));
    }

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return applies;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        return findings;
    }
}
