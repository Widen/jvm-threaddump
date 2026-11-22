package com.widen.util.td;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.widen.util.td.Line.BlankLine;
import static com.widen.util.td.Line.MultiLine;
import static com.widen.util.td.Line.TitledLine;

/**
 * The problems the checks found, printed before everything else.
 */
final class FindingsSection
{
    private final Findings findings;
    private final JvmSnapshot snapshot;

    FindingsSection(Findings findings, JvmSnapshot snapshot) {
        this.findings = findings;
        this.snapshot = snapshot;
    }

    List<Line> lines() {
        String window = snapshot.sampled()
                ? String.format(Locale.US, "sampled over %.1fs", snapshot.windowNanos / 1e9)
                : "not sampled";
        String checks = findings.checksRun + (findings.checksRun == 1 ? " check" : " checks");
        List<Line> lines = new ArrayList<>();
        if (findings.findings.isEmpty()) {
            lines.add(new TitledLine("Findings", "None (" + checks + ", " + window + ")"));
            lines.add(new BlankLine());
            return lines;
        }
        lines.add(new TitledLine("Findings", findings.checksFlagged + " of " + checks + " flagged something ("
                + window + ")"));
        lines.add(new BlankLine());
        for (Finding finding : findings.findings) {
            lines.add(new MultiLine(finding.title, finding.lines));
            lines.add(new BlankLine());
        }
        return lines;
    }
}
