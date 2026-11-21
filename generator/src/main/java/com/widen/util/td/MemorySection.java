package com.widen.util.td;

import java.lang.management.MemoryUsage;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.widen.util.td.BytesUtils.byteCountToDisplaySize;
import static com.widen.util.td.Line.BlankLine;
import static com.widen.util.td.Line.TitledLine;
import static java.lang.String.format;

/**
 * The heap size, the heap and non-heap usage, and the usage of each memory pool.
 */
final class MemorySection
{
    /**
     * Known pool names in print order: Parallel ({@code PS}), CMS, G1, and Serial heap pools, then the code and
     * metadata pools.
     */
    static final List<String> POOL_ORDER = Collections.unmodifiableList(Arrays.asList(
            "PS Eden Space", "Par Eden Space", "Par Survivor Space", "PS Survivor Space", "PS Old Gen", "CMS Old Gen",
            "G1 Eden Space", "G1 Survivor Space", "G1 Old Gen",
            "Eden Space", "Survivor Space", "Tenured Gen",
            "Code Cache", "CodeCache", "CodeHeap 'non-nmethods'", "CodeHeap 'profiled nmethods'",
            "CodeHeap 'non-profiled nmethods'", "Compressed Class Space", "Metaspace"));

    private static final String USAGE_LABEL = "used/committed, used/max";

    private final JvmSources jvm;
    private final JvmSnapshot snapshot;
    private final Findings findings;

    MemorySection(JvmSources jvm, JvmSnapshot snapshot, Findings findings) {
        this.jvm = jvm;
        this.snapshot = snapshot;
        this.findings = findings;
    }

    List<Line> lines() {
        List<Line> lines = new ArrayList<>();
        lines.add(new TitledLine("JVM Free Memory", format("%s (of committed)", byteCountToDisplaySize(jvm.freeMemory()))));
        lines.add(new TitledLine("JVM Maximum Heap", jvm.maxMemory() == Long.MAX_VALUE ? "unlimited" : byteCountToDisplaySize(jvm.maxMemory())));

        Set<String> memoryArgs = jvm.runtime().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-X"))
                .collect(Collectors.toCollection(TreeSet::new));
        lines.add(new TitledLine("JVM Memory Args", String.join(" ", memoryArgs)));
        lines.add(new BlankLine());

        lines.add(new TitledLine("Memory", USAGE_LABEL));
        lines.add(new TitledLine("Heap", formatUsage(jvm.memory().getHeapMemoryUsage())));
        lines.add(new TitledLine("Non Heap", formatUsage(jvm.memory().getNonHeapMemoryUsage())));
        lines.add(new BlankLine());

        lines.add(new TitledLine("Collector", USAGE_LABEL));
        Map<String, MemoryUsage> pools = new TreeMap<>();
        for (PoolSnapshot pool : snapshot.pools) {
            pools.put(pool.name, pool.usage);
        }
        for (String name : POOL_ORDER) {
            MemoryUsage usage = pools.remove(name);
            if (usage != null) {
                lines.add(new TitledLine(name, formatUsage(usage) + findings.marker(Finding.poolMark(name))));
            }
        }
        pools.forEach((name, usage) -> lines.add(
                new TitledLine(name + "*", formatUsage(usage) + findings.marker(Finding.poolMark(name)))));
        lines.add(new BlankLine());
        return lines;
    }

    /**
     * Formats {@code used/committed%} and {@code used/max%}, then the sizes. A percentage whose denominator is zero
     * or negative (an undefined maximum is -1) prints as 0%.
     */
    static String formatUsage(MemoryUsage usage) {
        NumberFormat percentFormat = NumberFormat.getPercentInstance(Locale.US);
        String percents = format("%s/%s",
                percentFormat.format(ratio(usage.getUsed(), usage.getCommitted())),
                percentFormat.format(ratio(usage.getUsed(), usage.getMax())));
        return format("%-12s  init=%-10s  used=%-10s  commit=%-10s  max=%-10s",
                percents,
                byteCountToDisplaySize(usage.getInit()),
                byteCountToDisplaySize(usage.getUsed()),
                byteCountToDisplaySize(usage.getCommitted()),
                byteCountToDisplaySize(usage.getMax()));
    }

    private static double ratio(long numerator, long denominator) {
        return denominator > 0 ? (double) numerator / (double) denominator : 0d;
    }
}
