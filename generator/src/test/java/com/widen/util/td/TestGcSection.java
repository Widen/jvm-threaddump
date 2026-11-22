package com.widen.util.td;

import org.junit.jupiter.api.Test;

import static com.widen.util.td.TestText.titled;
import static org.assertj.core.api.Assertions.assertThat;

public class TestGcSection
{
    @Test
    void printsEachCollector() {
        FakeJvm jvm = new FakeJvm();
        jvm.addCollector("G1 Young Generation", 12, 1234);
        jvm.addCollector("G1 Old Generation", 0, 0);

        assertThat(TestText.render(new GcSection(jvm.snapshot(), Findings.NONE).lines()))
                .containsExactly(
                        titled("GC G1 Young Generation", "spent 0:00:01.234 doing 12 collections"),
                        titled("GC G1 Old Generation", "spent 0:00:00.000 doing 0 collections"),
                        "");
    }

    @Test
    void marksACollectorLine() {
        FakeJvm jvm = new FakeJvm();
        jvm.addCollector("G1 Young Generation", 12, 1234);
        JvmSnapshot snapshot = jvm.snapshot();
        Findings findings = StubCheck.findingsOf(snapshot,
                StubCheck.finding("Alpha", Finding.gcMark("G1 Young Generation")));

        assertThat(TestText.render(new GcSection(snapshot, findings).lines()))
                .contains(titled("GC G1 Young Generation", "spent 0:00:01.234 doing 12 collections <<< Alpha"));
    }

    @Test
    void printsNotAvailableForUndefinedCollectionTime() {
        FakeJvm jvm = new FakeJvm();
        jvm.addCollector("Copy", 3, -1);

        assertThat(TestText.render(new GcSection(jvm.snapshot(), Findings.NONE).lines()))
                .contains(titled("GC Copy", "spent Not Available doing 3 collections"));
    }
}
