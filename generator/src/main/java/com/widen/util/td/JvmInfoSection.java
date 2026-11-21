package com.widen.util.td;

import java.lang.management.OperatingSystemMXBean;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.widen.util.td.BytesUtils.byteCountToDisplaySize;
import static com.widen.util.td.Durations.NOT_AVAILABLE;
import static com.widen.util.td.Line.BlankLine;
import static com.widen.util.td.Line.MultiLine;
import static com.widen.util.td.Line.TitledLine;
import static java.lang.String.format;

/**
 * The time, Java runtime, process, and host details at the top of the dump.
 */
final class JvmInfoSection
{
    /** The title of the line that {@link CpuSaturatedCheck} marks. */
    static final String CPU_LOAD = "CPU Load";
    /** The title of the line that {@link FileDescriptorsCheck} marks. */
    static final String FILE_DESCRIPTORS = "File Descriptors";

    private static final String MASK = "****";
    private static final List<String> SECRET_WORDS = Arrays.asList(
            "password", "pass", "pwd", "secret", "token", "credential", "key");
    private static final Pattern URL_PASSWORD = Pattern.compile("://[^/@:]*:[^/@]*@");

    private final JvmSources jvm;
    private final JvmSnapshot snapshot;
    private final Findings findings;
    private final List<String> mainArguments;
    private final Map<String, ?> customValues;

    JvmInfoSection(JvmSources jvm, JvmSnapshot snapshot, Findings findings, List<String> mainArguments,
            Map<String, ?> customValues) {
        this.jvm = jvm;
        this.snapshot = snapshot;
        this.findings = findings;
        this.mainArguments = mainArguments;
        this.customValues = customValues;
    }

    List<Line> lines() {
        List<Line> lines = new ArrayList<>();
        TimeZone timeZone = jvm.timeZone();
        SimpleDateFormat currentTime = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss z", Locale.US);
        currentTime.setTimeZone(timeZone);
        lines.add(new TitledLine("Current Time", currentTime.format(Date.from(jvm.clock().instant()))));
        lines.add(new TitledLine("Default Time Zone", timeZone.getDisplayName(Locale.US) + " (" + timeZone.getID() + ")"));
        lines.add(new TitledLine("Java Version", format("%s (%s)", jvm.systemProperty("java.runtime.version"), jvm.systemProperty("java.vendor"))));
        lines.add(new TitledLine("Java VM", format("%s (%s)", jvm.systemProperty("java.vm.version"), jvm.systemProperty("java.vm.info"))));
        lines.add(new TitledLine("Java Home", jvm.systemProperty("java.home")));
        lines.add(new TitledLine("Working Directory", jvm.systemProperty("user.dir")));
        lines.add(new TitledLine("Temp Directory", jvm.systemProperty("java.io.tmpdir")));
        lines.add(new BlankLine());

        if (!customValues.isEmpty()) {
            for (Map.Entry<String, ?> entry : customValues.entrySet()) {
                lines.add(new TitledLine(entry.getKey(), String.valueOf(entry.getValue())));
            }
            lines.add(new BlankLine());
        }

        lines.add(new TitledLine("Main Arguments", String.join(" ", mainArguments)));
        lines.add(new TitledLine("Default Encoding", jvm.systemProperty("file.encoding")));
        lines.add(new MultiLine("JVM System Props", systemProperties()));
        lines.add(new BlankLine());

        OperatingSystemMXBean os = jvm.operatingSystem();
        lines.add(new TitledLine("Hostname", Optional.ofNullable(jvm.environmentVariable("HOSTNAME")).orElse(NOT_AVAILABLE)));
        lines.add(new TitledLine("Operating System", format("%s (%s)", os.getName(), os.getVersion())));
        lines.add(new TitledLine("System Processors", format("%s %s", os.getAvailableProcessors(), os.getArch())));
        com.sun.management.OperatingSystemMXBean sunOs = SunManagement.operatingSystem(os);
        if (sunOs != null) {
            long freeMemory = sunOs.getFreePhysicalMemorySize();
            long totalMemory = sunOs.getTotalPhysicalMemorySize();
            lines.add(new TitledLine("System Memory", freeMemory < 0 || totalMemory < 0
                    ? NOT_AVAILABLE
                    : format("%s available; %s total", byteCountToDisplaySize(freeMemory), byteCountToDisplaySize(totalMemory))));
        }
        else {
            lines.add(new TitledLine("System Memory", NOT_AVAILABLE));
        }
        String fileDescriptors = snapshot.openFileDescriptors < 0 || snapshot.maxFileDescriptors < 0
                ? NOT_AVAILABLE
                : format(Locale.US, "%,d open; %,d limit", snapshot.openFileDescriptors, snapshot.maxFileDescriptors);
        lines.add(new TitledLine(FILE_DESCRIPTORS,
                fileDescriptors + findings.marker(Finding.lineMark(FILE_DESCRIPTORS))));
        String cpuLoad = snapshot.systemCpuLoad < 0 || snapshot.processCpuLoad < 0
                ? NOT_AVAILABLE
                : format(Locale.US, "%.4f system; %.4f jvm (0.0 to 1.0)", snapshot.systemCpuLoad, snapshot.processCpuLoad);
        lines.add(new TitledLine(CPU_LOAD, cpuLoad + findings.marker(Finding.lineMark(CPU_LOAD))));

        long[] uptime = systemUptimeSeconds();
        lines.add(new TitledLine("System Uptime", formatTime(uptime[0], TimeUnit.SECONDS)));
        lines.add(new TitledLine("System Idle Time", formatTime(uptime[1], TimeUnit.SECONDS)));
        lines.add(new TitledLine("JVM Uptime", formatTime(snapshot.uptimeMillis, TimeUnit.MILLISECONDS)));
        lines.add(new TitledLine("JVM CPU Time", sunOs != null
                ? formatTime(sunOs.getProcessCpuTime(), TimeUnit.NANOSECONDS)
                : NOT_AVAILABLE));
        lines.add(new BlankLine());
        return lines;
    }

    /**
     * Masks the value of a {@code key=value} system property that may hold a secret: the key names one, or the value
     * holds {@code word=} for one (as in a JDBC URL or an options string), or the value holds a URL password
     * ({@code ://user:password@}). A property without {@code =} is unchanged.
     *
     * @param property the property without its {@code -D} prefix
     */
    static String maskSecret(String property) {
        int separator = property.indexOf('=');
        if (separator < 0) {
            return property;
        }
        String key = property.substring(0, separator);
        String value = property.substring(separator + 1);
        if (containsSecretWord(key, "") || containsSecretWord(value, "=") || URL_PASSWORD.matcher(value).find()) {
            return key + "=" + MASK;
        }
        return property;
    }

    private static boolean containsSecretWord(String text, String suffix) {
        String lowerCase = text.toLowerCase(Locale.ROOT);
        return SECRET_WORDS.stream().anyMatch(word -> lowerCase.contains(word + suffix));
    }

    private Collection<String> systemProperties() {
        return jvm.runtime().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-D"))
                .map(argument -> maskSecret(argument.substring(2)))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * @return the uptime and idle seconds, or two -1 values when {@code /proc/uptime} is missing or does not hold
     *         two numbers
     */
    private long[] systemUptimeSeconds() {
        String[] fields = jvm.procUptime().map(text -> text.trim().split("\\s+")).orElse(new String[0]);
        try {
            if (fields.length == 2) {
                return new long[] { Double.valueOf(fields[0]).longValue(), Double.valueOf(fields[1]).longValue() };
            }
        }
        catch (NumberFormatException e) {
            // falls through to the not-available result
        }
        return new long[] { -1, -1 };
    }

    private static String formatTime(long value, TimeUnit unit) {
        if (value < 0) {
            return NOT_AVAILABLE;
        }
        return Durations.daysHoursMinutesSeconds(unit.toMillis(value));
    }
}
