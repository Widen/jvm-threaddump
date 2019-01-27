package com.widen.util.td;

import com.sun.management.OperatingSystemMXBean;
import sun.management.ManagementFactoryHelper;

import java.io.FileInputStream;
import java.io.PrintWriter;
import java.lang.management.*;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.widen.util.td.BytesUtils.byteCountToDisplaySize;
import static java.lang.String.format;
import static java.lang.System.getProperty;

public class TextOutput {

    private PrintWriter out;

    private static final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private static final OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private static final List<MemoryPoolMXBean> memoryPoolBean = ManagementFactoryHelper.getMemoryPoolMXBeans();
    private static final List<GarbageCollectorMXBean> gcBean = ManagementFactory.getGarbageCollectorMXBeans();

    public TextOutput(PrintWriter out) {
        this.out = out;
    }

    public void generate(PrintWriter out) {
        List<Line> lines = new ArrayList<>();

        lines.add(Line.single("Current Time", new SimpleDateFormat("MM/dd/yyyy HH:mm:ss z").format(new Date())));
        lines.add(Line.single("Time Zone", TimeZone.getDefault().getDisplayName() + " (" + TimeZone.getDefault().getID() + ")"));
        lines.add(Line.single("Java Version", format("%s (%s)", getProperty("java.runtime.version"), getProperty("java.vendor"))));
        lines.add(Line.single("Java VM", format("%s (%s)", getProperty("java.vm.version"), getProperty("java.vm.info"))));
        lines.add(Line.single("Java Home", getProperty("java.home")));
        lines.add(Line.single("Working Directory", getProperty("user.dir")));
        lines.add(Line.single("Temp Directory", getProperty("java.io.tmpdir")));
        lines.add(Line.blank());

        lines.add(Line.single("Main Arguments", String.join(" ", runtimeBean.getInputArguments())));
        lines.add(Line.single("Default Encoding", getProperty("file.encoding")));
        lines.add(Line.multiple("JVM System Props", systemProperties()));
        lines.add(Line.blank());

        lines.add(Line.single("Hostname", Optional.ofNullable(System.getenv("HOSTNAME")).orElse("Not Available")));
        lines.add(Line.single("Operating System", format("%s (%s)", osBean.getName(), osBean.getVersion())));
        lines.add(Line.single("System Processors", format("%s %s", osBean.getAvailableProcessors(), osBean.getArch())));
        lines.add(Line.single("System Memory", String.format("%s available; %s total", byteCountToDisplaySize(osBean.getFreePhysicalMemorySize()), byteCountToDisplaySize(osBean.getTotalPhysicalMemorySize()))));
        lines.add(Line.single("CPU Load", String.format("%.4f system; %.4f jvm", osBean.getSystemCpuLoad(), osBean.getProcessCpuLoad())));

        Uptime systemUptime = systemUptimeSeconds();
        lines.add(Line.single("System Uptime", formatTime(systemUptime.uptime, TimeUnit.SECONDS)));
        lines.add(Line.single("System Idle Time", formatTime(systemUptime.idletime, TimeUnit.SECONDS)));
        lines.add(Line.single("JVM Uptime", formatTime(runtimeBean.getUptime(), TimeUnit.MILLISECONDS)));
        lines.add(Line.single("JVM CPU Time", formatTime(osBean.getProcessCpuTime(), TimeUnit.NANOSECONDS)));
        lines.add(Line.blank());

        lines.add(Line.single("JVM Free Memory", byteCountToDisplaySize(Runtime.getRuntime().freeMemory())));
        lines.add(Line.single("JVM Maximum Heap", Runtime.getRuntime().maxMemory() == Long.MAX_VALUE ? "unlimited" : byteCountToDisplaySize(Runtime.getRuntime().maxMemory())));

        //out.print(leftPad("JVM Memory Args: ", 25));
        Set<String> memoryArgs = runtimeBean.getInputArguments().stream()
                .filter(s -> s.startsWith("-X"))
                .collect(Collectors.toCollection(TreeSet::new));
        lines.add(Line.single("JVM Memory Args", String.join(" ", memoryArgs)));
        lines.add(Line.blank());

        lines.add(Line.single("", "used/max"));
        lines.add(Line.single("Heap Memory", formatMemoryUsage(memoryBean.getHeapMemoryUsage())));
        lines.add(Line.single("Non Heap Memory", formatMemoryUsage(memoryBean.getNonHeapMemoryUsage())));
        lines.add(Line.blank());

        // "PS Eden Space", "PS Survivor Space", "PS Old Gen" == -XX:+UseParallelGC, Sun Parallel GC
        // "CMS Old Gen", "CMS Perm Gen" == -XX:+UseConcMarkSweepGC, Sun Concurrent Mark Sweep GC
        // "Par Eden Space", "Par Survivor Space" == -XX:+UseParNewGC, Parallel GC for young (OK to use with CMS GC)
        // "G1 Eden", "G1 Survivor", "G1 Old Gen", "G1 Perm Gen" == -X G1

        lines.add(Line.single("Collector", "used/max"));

        String[] poolPrintOrder = new String[]{"PS Eden Space", "Par Eden Space", "Par Survivor Space",
                "PS Survivor Space", "PS Old Gen", "CMS Old Gen", "PS Perm Gen",
                "CMS Perm Gen", "G1 Eden", "G1 Survivor", "G1 Old Gen", "G1 Perm Gen",
                "Code Cache", "Compressed Class Space", "Metaspace"};

        List<String> printedPools = new ArrayList<>();
        Map<String, MemoryUsage> pools = new HashMap<>();
        for (MemoryPoolMXBean memoryPool : memoryPoolBean) {
            pools.put(memoryPool.getName(), memoryPool.getUsage());
        }
        for (String poolKey : poolPrintOrder) {
            MemoryUsage usage = pools.get(poolKey);
            if (usage != null) {
                printedPools.add(poolKey);
                lines.add(Line.single(poolKey, formatMemoryUsage(usage)));
            }
        }

        for (String pool : pools.keySet()) {
            if (!printedPools.contains(pool)) {
                lines.add(Line.single(pool + "*", formatMemoryUsage(pools.get(pool))));
            }
        }

        ThreadDumpProducer producer = new ThreadDumpProducer();
        // DEADLOCKS
        Map<Thread, StackTraceElement[]> threads = producer.getThreads();
        lines.add(Line.single("", String.format("___ %s active threads ___", threads.size())));
        for (Map.Entry<Thread, StackTraceElement[]> entry : threads.entrySet()) {
            lines.add(Line.single("", ThreadDumpProducer.formatThread(entry.getKey())));
            for (StackTraceElement frame : entry.getValue()) {
                lines.add(Line.single("", ThreadDumpProducer.formatFrame(frame)));
            }
            lines.add(Line.blank());
        }
    }

    private static class Line {
        String title;
        List<String> rows;

        static Line single(String title, String row) {
            Line line = new Line();
            line.title = title;
            line.rows = Arrays.asList(row);
            return line;
        }

        static Line multiple(String title, Collection<String> rows) {
            Line line = new Line();
            line.title = title;
            line.rows = new ArrayList<>(rows);
            return line;
        }

        static Line blank() {
            return new Line();
        }
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
        return DurationFormatUtils.formatDuration(millis, "d 'days', H:mm:ss", true);
    }

    private Uptime systemUptimeSeconds() {
        try (Scanner scanner = new Scanner(new FileInputStream("/proc/uptime"))) {
            String uptime = scanner.next();
            String idleTime = scanner.next();
            return new Uptime(Double.valueOf(uptime).longValue(), Double.valueOf(idleTime).longValue());
        } catch (Exception e) {
            return new Uptime(-1, -1);
        }
    }

    private class Uptime {
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

        return String.format("%s/%s  init=%s  used=%s  commit=%s  max=%s",
                percentFormat.format(percentUsedOfCommitted), percentFormat.format(percentUsedOfMax),
                byteCountToDisplaySize(usage.getInit()),
                byteCountToDisplaySize(usage.getUsed()),
                byteCountToDisplaySize(usage.getCommitted()),
                byteCountToDisplaySize(usage.getMax()));
    }

    public void garbageCollections(List<GarbageCollectorMXBean> garbageCollectors) {
        for (GarbageCollectorMXBean gc : garbageCollectors) {
            //out.println(leftPad("GC " + gc.getName() + ": ", 25) + "spent " + DurationFormatUtils.formatDuration(gc.getCollectionTime(), "H:mm:ss") + " doing " + gc.getCollectionCount() + " collections");
        }
        out.println();
    }

    private void printCollectionOnMultipleRows(Collection<String> input, int maxRowLength) {
        List<String> items = new ArrayList<>();
        items.addAll(input);

        Collections.sort(items);

        boolean addLineBreak = false;
        int rowLength = 0;

        for (int i = 0; i < items.size(); i++) {
            String text = items.get(i);

            if (i > 0) {
                out.print(", ");
                if (addLineBreak) {
                    addLineBreak = false;
                    rowLength = 0;
                    out.print("\n" + StringUtils.repeat(" ", 25));
                }
            }

            if (i < (items.size() - 1)) {
                rowLength += text.length();
                String peekText = items.get(i + 1);
                addLineBreak = (rowLength + peekText.length()) > maxRowLength;
            }

            out.print(text);
        }

        out.println();
    }


    public void deadlockedThreads(long[] deadlockedThreads) {
        out.print("*** deadlocks: ");
        for (long deadlockedThread : deadlockedThreads) {
            out.print("0x" + Long.toHexString(deadlockedThread) + " ");
        }
        out.println();
        out.println();
    }

}
