package com.widen.util.td;

import org.junit.jupiter.api.Test;

import static com.widen.util.td.StubCheck.finding;
import static org.assertj.core.api.Assertions.assertThat;

public class TestFindings
{
    @Test
    void marksALineWithEachDistinctTitleInOrder() {
        Findings findings = StubCheck.findingsOf(new FakeJvm().snapshot(),
                finding("Alpha", Finding.poolMark("X")),
                finding("Alpha", Finding.poolMark("X")),
                finding("Beta", Finding.poolMark("X"), Finding.threadMark(23)));

        assertThat(findings.marker("pool:X")).isEqualTo(" <<< Alpha, Beta");
        assertThat(findings.marker("thread:23")).isEqualTo(" <<< Beta");
        assertThat(findings.marker("pool:Y")).isEmpty();
    }

    @Test
    void buildsMarksFromTheKindAndName() {
        assertThat(Finding.threadMark(23)).isEqualTo("thread:23");
        assertThat(Finding.poolMark("G1 Old Gen")).isEqualTo("pool:G1 Old Gen");
        assertThat(Finding.gcMark("G1 Young Generation")).isEqualTo("gc:G1 Young Generation");
        assertThat(Finding.lineMark("CPU Load")).isEqualTo("line:CPU Load");
    }
}
