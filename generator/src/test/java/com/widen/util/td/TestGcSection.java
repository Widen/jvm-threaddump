package com.widen.util.td;

import java.lang.management.GarbageCollectorMXBean;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static com.widen.util.td.TestText.titled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestGcSection
{
    @Test
    void printsEachCollector() {
        FakeJvm jvm = new FakeJvm();
        jvm.garbageCollectors.addAll(Arrays.asList(gc("G1 Young Generation", 12, 1234), gc("G1 Old Generation", 0, 0)));

        assertThat(TestText.render(new GcSection(jvm).lines()))
                .containsExactly(
                        titled("GC G1 Young Generation", "spent 0:00:01.234 doing 12 collections"),
                        titled("GC G1 Old Generation", "spent 0:00:00.000 doing 0 collections"),
                        "");
    }

    @Test
    void printsNotAvailableForUndefinedCollectionTime() {
        FakeJvm jvm = new FakeJvm();
        jvm.garbageCollectors.add(gc("Copy", 3, -1));

        assertThat(TestText.render(new GcSection(jvm).lines()))
                .contains(titled("GC Copy", "spent Not Available doing 3 collections"));
    }

    private static GarbageCollectorMXBean gc(String name, long count, long millis) {
        GarbageCollectorMXBean gc = mock(GarbageCollectorMXBean.class);
        when(gc.getName()).thenReturn(name);
        when(gc.getCollectionCount()).thenReturn(count);
        when(gc.getCollectionTime()).thenReturn(millis);
        return gc;
    }
}
