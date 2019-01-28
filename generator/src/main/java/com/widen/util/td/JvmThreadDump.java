package com.widen.util.td;

import java.io.FileInputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.sun.management.OperatingSystemMXBean;

import static com.widen.util.td.BytesUtils.byteCountToDisplaySize;
import static com.widen.util.td.DurationFormatUtils.formatDuration;
import static com.widen.util.td.Line.BlankLine;
import static com.widen.util.td.Line.MultiLine;
import static com.widen.util.td.Line.TitledLine;
import static java.lang.String.format;
import static java.lang.System.getProperty;

public class JvmThreadDump
{

    private List<Line> lines = new ArrayList<>();

    private static final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private static final OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private static final List<MemoryPoolMXBean> memoryPoolBeans = ManagementFactory.getMemoryPoolMXBeans();
    private static final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private static final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    public JvmThreadDump() {
    }

    public String generate() {
        doJvmInfo();
        doJvmMemory();
        doGc();
        doDeadlocks();
        doThreads();

        StringBuilder sb = new StringBuilder(10_000);
        for (Line line : lines) {
            sb.append(line.toString());
        }
        return sb.toString();
    }

    private void doJvmInfo() {
        lines.add(new TitledLine("Current Time", new SimpleDateFormat("MM/dd/yyyy HH:mm:ss z").format(new Date())));
        lines.add(new TitledLine("Time Zone", TimeZone.getDefault().getDisplayName() + " (" + TimeZone.getDefault().getID() + ")"));
        lines.add(new TitledLine("Java Version", format("%s (%s)", getProperty("java.runtime.version"), getProperty("java.vendor"))));
        lines.add(new TitledLine("Java VM", format("%s (%s)", getProperty("java.vm.version"), getProperty("java.vm.info"))));
        lines.add(new TitledLine("Java Home", getProperty("java.home")));
        lines.add(new TitledLine("Working Directory", getProperty("user.dir")));
        lines.add(new TitledLine("Temp Directory", getProperty("java.io.tmpdir")));
        lines.add(new BlankLine());

        lines.add(new TitledLine("Main Arguments", String.join(" ", runtimeBean.getInputArguments())));
        lines.add(new TitledLine("Default Encoding", getProperty("file.encoding")));
        lines.add(new MultiLine("JVM System Props", systemProperties()));
        lines.add(new BlankLine());

        lines.add(new TitledLine("Hostname", Optional.ofNullable(System.getenv("HOSTNAME")).orElse("Not Available")));
        lines.add(new TitledLine("Operating System", format("%s (%s)", osBean.getName(), osBean.getVersion())));
        lines.add(new TitledLine("System Processors", format("%s %s", osBean.getAvailableProcessors(), osBean.getArch())));
        lines.add(new TitledLine("System Memory", String.format("%s available; %s total", byteCountToDisplaySize(osBean.getFreePhysicalMemorySize()), byteCountToDisplaySize(osBean.getTotalPhysicalMemorySize()))));
        lines.add(new TitledLine("CPU Load", String.format("%.4f system; %.4f jvm", osBean.getSystemCpuLoad(), osBean.getProcessCpuLoad())));

        Uptime systemUptime = systemUptimeSeconds();
        lines.add(new TitledLine("System Uptime", formatTime(systemUptime.uptime, TimeUnit.SECONDS)));
        lines.add(new TitledLine("System Idle Time", formatTime(systemUptime.idletime, TimeUnit.SECONDS)));
        lines.add(new TitledLine("JVM Uptime", formatTime(runtimeBean.getUptime(), TimeUnit.MILLISECONDS)));
        lines.add(new TitledLine("JVM CPU Time", formatTime(osBean.getProcessCpuTime(), TimeUnit.NANOSECONDS)));
        lines.add(new BlankLine());
    }

    private Collection<String> systemProperties() {
        return runtimeBean.getInputArguments().stream()
                .filter(s -> s.startsWith("-D"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private String formatTime(long value, TimeUnit unit) {
        if (value == -1) {
            return "Not Available";
        }
        long millis = unit.toMillis(value);
        return formatDuration(millis, "d 'days', H:mm:ss", true);
    }

    private Uptime systemUptimeSeconds() {
        try (Scanner scanner = new Scanner(new FileInputStream("/proc/uptime"))) {
            String uptime = scanner.next();
            String idleTime = scanner.next();
            return new Uptime(Double.valueOf(uptime).longValue(), Double.valueOf(idleTime).longValue());
        }
        catch (Exception e) {
            return new Uptime(-1, -1);
        }
    }

    private class Uptime
    {

        long uptime;

        long idletime;
        public Uptime(long uptime, long idletime) {
            this.uptime = uptime;
            this.idletime = idletime;
        }

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
        lines.add(new TitledLine("JVM Free Memory", byteCountToDisplaySize(Runtime.getRuntime().freeMemory())));
        lines.add(new TitledLine("JVM Maximum Heap", Runtime.getRuntime().maxMemory() == Long.MAX_VALUE ? "unlimited" : byteCountToDisplaySize(Runtime.getRuntime().maxMemory())));

        Set<String> memoryArgs = runtimeBean.getInputArguments().stream()
                .filter(s -> s.startsWith("-X"))
                .collect(Collectors.toCollection(TreeSet::new));
        lines.add(new TitledLine("JVM Memory Args", String.join(" ", memoryArgs)));
        lines.add(new BlankLine());

        lines.add(new TitledLine("Memory", "used/max"));
        lines.add(new TitledLine("Heap", formatMemoryUsage(memoryBean.getHeapMemoryUsage())));
        lines.add(new TitledLine("Non Heap", formatMemoryUsage(memoryBean.getNonHeapMemoryUsage())));
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
        for (MemoryPoolMXBean memoryPool : memoryPoolBeans) {
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

    public void doGc() {
        for (GarbageCollectorMXBean gc : gcBeans) {
            lines.add(new TitledLine("GC " + gc.getName(), String.format("spent %s doing %d collections", formatDuration(gc.getCollectionTime(), "H:mm:ss", true), gc.getCollectionCount())));
        }
        lines.add(new Line.BlankLine());
    }

    public void doDeadlocks() {
        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
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
