package com.widen.util.td;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.widen.util.td.BytesUtils.byteCountToDisplaySize;
import static com.widen.util.td.Line.BlankLine;
import static com.widen.util.td.Line.TitledLine;

public class JvmThreadDumpImpl implements JvmThreadDump
{

    private List<Line> lines = new ArrayList<>();

    private final JvmSources jvm;

    public JvmThreadDumpImpl() {
        this(JvmSources.platform());
    }

    JvmThreadDumpImpl(JvmSources jvm) {
        this.jvm = jvm;
    }

    @Override
    public String generate() {
        lines.addAll(new JvmInfoSection(jvm, getMainArguments(), getCustomValues()).lines());
        doJvmMemory();
        doGc();
        doDeadlocks();
        doThreads();

        return Line.render(lines);
    }

    /**
     * Overridable if another mechanism is preferred...
     */
    protected List<String> getMainArguments() {
        return Collections.emptyList();
    }

    /**
     * Custom values to be outputted...
     */
    protected Map<String, Object> getCustomValues() {
        return Collections.emptyMap();
    }

    private String formatMemoryUsage(MemoryUsage usage) {
        NumberFormat percentFormat = NumberFormat.getPercentInstance();

        double percentUsedOfCommitted = (double) usage.getUsed() / (double) usage.getCommitted();
        double percentUsedOfMax = usage.getMax() > 0d ? (double) usage.getUsed() / (double) usage.getMax() : 0d;

        return String.format("%-12s  init=%-10s  used=%-10s  commit=%-10s  max=%-10s",
                String.format("%s/%s", percentFormat.format(percentUsedOfCommitted), percentFormat.format(percentUsedOfMax)),
                byteCountToDisplaySize(usage.getInit()),
                byteCountToDisplaySize(usage.getUsed()),
                byteCountToDisplaySize(usage.getCommitted()),
                byteCountToDisplaySize(usage.getMax()));
    }

    private void doJvmMemory() {
        lines.add(new TitledLine("JVM Free Memory", String.format("%s (of committed)", byteCountToDisplaySize(jvm.freeMemory()))));
        lines.add(new TitledLine("JVM Maximum Heap", jvm.maxMemory() == Long.MAX_VALUE ? "unlimited" : byteCountToDisplaySize(jvm.maxMemory())));

        Set<String> memoryArgs = jvm.runtime().getInputArguments().stream()
                .filter(s -> s.startsWith("-X"))
                .collect(Collectors.toCollection(TreeSet::new));
        lines.add(new TitledLine("JVM Memory Args", String.join(" ", memoryArgs)));
        lines.add(new BlankLine());

        lines.add(new TitledLine("Memory", "used/max"));
        lines.add(new TitledLine("Heap", formatMemoryUsage(jvm.memory().getHeapMemoryUsage())));
        lines.add(new TitledLine("Non Heap", formatMemoryUsage(jvm.memory().getNonHeapMemoryUsage())));
        lines.add(new BlankLine());

        // "PS Eden Space", "PS Survivor Space", "PS Old Gen" == -XX:+UseParallelGC, Sun Parallel GC
        // "CMS Old Gen", "CMS Perm Gen" == -XX:+UseConcMarkSweepGC, Sun Concurrent Mark Sweep GC
        // "Par Eden Space", "Par Survivor Space" == -XX:+UseParNewGC, Parallel GC for young (OK to use with CMS GC)
        // "G1 Eden", "G1 Survivor", "G1 Old Gen", "G1 Perm Gen" == -X G1

        lines.add(new TitledLine("Collector", "used/max"));

        String[] poolPrintOrder = new String[] { "PS Eden Space", "Par Eden Space", "Par Survivor Space",
                "PS Survivor Space", "PS Old Gen", "CMS Old Gen", "PS Perm Gen",
                "CMS Perm Gen", "G1 Eden", "G1 Survivor", "G1 Old Gen", "G1 Perm Gen",
                "Code Cache", "Compressed Class Space", "Metaspace" };

        List<String> printedPools = new ArrayList<>();
        Map<String, MemoryUsage> pools = new HashMap<>();
        for (MemoryPoolMXBean memoryPool : jvm.memoryPools()) {
            pools.put(memoryPool.getName(), memoryPool.getUsage());
        }
        for (String poolKey : poolPrintOrder) {
            MemoryUsage usage = pools.get(poolKey);
            if (usage != null) {
                printedPools.add(poolKey);
                lines.add(new TitledLine(poolKey, formatMemoryUsage(usage)));
            }
        }

        for (String pool : pools.keySet()) {
            if (!printedPools.contains(pool)) {
                lines.add(new TitledLine(pool + "*", formatMemoryUsage(pools.get(pool))));
            }
        }

        lines.add(new Line.BlankLine());
    }

    private void doGc() {
        for (GarbageCollectorMXBean gc : jvm.garbageCollectors()) {
            lines.add(new TitledLine("GC " + gc.getName(), String.format("spent %s doing %d collections", Durations.hoursMinutesSecondsMillis(gc.getCollectionTime()), gc.getCollectionCount())));
        }
        lines.add(new Line.BlankLine());
    }

    private void doDeadlocks() {
        long[] deadlockedThreads = jvm.threads().findDeadlockedThreads();
        if (deadlockedThreads != null) {
            List<String> ids = Arrays.stream(deadlockedThreads).mapToObj(l -> "0x" + Long.toHexString(l)).collect(Collectors.toList());
            lines.add(new TitledLine("Deadlocked Threads", String.join(", ", ids)));
        }
        else {
            lines.add(new Line.TitledLine("Deadlocked Threads", "None"));
        }
    }

    private void doThreads() {
        ThreadDumpProducer producer = new ThreadDumpProducer();
        Map<Thread, StackTraceElement[]> threads = producer.getThreads();
        lines.add(new Line.TitledLine("Thread Count", String.valueOf(threads.size())));
        lines.add(new Line.BlankLine());
        for (Map.Entry<Thread, StackTraceElement[]> entry : threads.entrySet()) {
            lines.add(new Line.SimpleLine(ThreadDumpProducer.formatThread(entry.getKey())));
            for (StackTraceElement frame : entry.getValue()) {
                lines.add(new Line.SimpleLine(ThreadDumpProducer.formatFrame(frame)));
            }
            lines.add(new BlankLine());
        }
    }

}
