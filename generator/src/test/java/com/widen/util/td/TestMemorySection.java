package com.widen.util.td;

import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.widen.util.td.FakeJvm.MB;
import static com.widen.util.td.TestText.titled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestMemorySection
{
    private static final MemoryUsage POOL_USAGE = new MemoryUsage(MB, 2 * MB, 4 * MB, 8 * MB);

    static Stream<Arguments> formatsUsageAsPercentsAndSizes() {
        return Stream.of(
                arguments(new MemoryUsage(256 * MB, 64 * MB, 128 * MB, 256 * MB),
                        "50%/25%       init=256 MB      used=64 MB       commit=128 MB      max=256 MB    "),
                arguments(new MemoryUsage(2 * MB, 8 * MB, 16 * MB, -1),
                        "50%/0%        init=2 MB        used=8 MB        commit=16 MB       max=-1 bytes  "),
                arguments(new MemoryUsage(0, 0, 0, MB),
                        "0%/0%         init=0 bytes     used=0 bytes     commit=0 bytes     max=1 MB      "));
    }

    @ParameterizedTest
    @MethodSource
    void formatsUsageAsPercentsAndSizes(MemoryUsage usage, String expected) {
        assertThat(MemorySection.formatUsage(usage)).isEqualTo(expected);
    }

    @Test
    void printsHeapSummaryAndPools() {
        FakeJvm jvm = new FakeJvm();
        String usage = MemorySection.formatUsage(POOL_USAGE);
        jvm.memoryPools.addAll(Arrays.asList(
                pool("Metaspace", POOL_USAGE),
                pool("Some Pool", POOL_USAGE),
                pool("G1 Old Gen", POOL_USAGE),
                pool("Another Pool", POOL_USAGE),
                pool("G1 Eden Space", POOL_USAGE),
                pool("G1 Survivor Space", null)));

        assertThat(TestText.render(new MemorySection(jvm).lines()))
                .containsExactly(
                        titled("JVM Free Memory", "100 MB (of committed)"),
                        titled("JVM Maximum Heap", "512 MB"),
                        titled("JVM Memory Args", "-Xmx512m"),
                        "",
                        titled("Memory", "used/committed, used/max"),
                        titled("Heap", MemorySection.formatUsage(jvm.memory.getHeapMemoryUsage())),
                        titled("Non Heap", MemorySection.formatUsage(jvm.memory.getNonHeapMemoryUsage())),
                        "",
                        titled("Collector", "used/committed, used/max"),
                        titled("G1 Eden Space", usage),
                        titled("G1 Old Gen", usage),
                        titled("Metaspace", usage),
                        titled("Another Pool*", usage),
                        titled("Some Pool*", usage),
                        "");
    }

    static Stream<Arguments> printsKnownPoolsInCollectorOrder() {
        return Stream.of(
                arguments(Arrays.asList("PS Old Gen", "PS Survivor Space", "PS Eden Space"),
                        Arrays.asList("PS Eden Space", "PS Survivor Space", "PS Old Gen")),
                arguments(Arrays.asList("CMS Old Gen", "Par Survivor Space", "Par Eden Space"),
                        Arrays.asList("Par Eden Space", "Par Survivor Space", "CMS Old Gen")),
                arguments(Arrays.asList("G1 Old Gen", "G1 Survivor Space", "G1 Eden Space"),
                        Arrays.asList("G1 Eden Space", "G1 Survivor Space", "G1 Old Gen")),
                arguments(Arrays.asList("Tenured Gen", "Survivor Space", "Eden Space"),
                        Arrays.asList("Eden Space", "Survivor Space", "Tenured Gen")),
                arguments(Arrays.asList("Metaspace", "CodeHeap 'non-profiled nmethods'", "Compressed Class Space",
                                "CodeHeap 'profiled nmethods'", "CodeHeap 'non-nmethods'"),
                        Arrays.asList("CodeHeap 'non-nmethods'", "CodeHeap 'profiled nmethods'",
                                "CodeHeap 'non-profiled nmethods'", "Compressed Class Space", "Metaspace")));
    }

    @ParameterizedTest
    @MethodSource
    void printsKnownPoolsInCollectorOrder(List<String> reported, List<String> printed) {
        FakeJvm jvm = new FakeJvm();
        for (String name : reported) {
            jvm.memoryPools.add(pool(name, POOL_USAGE));
        }

        List<String> lines = TestText.render(new MemorySection(jvm).lines());
        List<String> poolLines = lines.subList(lines.indexOf(titled("Collector", "used/committed, used/max")) + 1,
                lines.size() - 1);
        List<String> titles = new ArrayList<>();
        for (String line : poolLines) {
            titles.add(line.substring(0, line.indexOf(':')).trim());
        }
        assertThat(titles).containsExactlyElementsOf(printed);
    }

    @Test
    void printsUnlimitedMaximumHeap() {
        FakeJvm jvm = new FakeJvm();
        jvm.maxMemory = Long.MAX_VALUE;

        assertThat(TestText.render(new MemorySection(jvm).lines())).contains(titled("JVM Maximum Heap", "unlimited"));
    }

    private static MemoryPoolMXBean pool(String name, MemoryUsage usage) {
        MemoryPoolMXBean pool = mock(MemoryPoolMXBean.class);
        when(pool.getName()).thenReturn(name);
        when(pool.getUsage()).thenReturn(usage);
        return pool;
    }
}
