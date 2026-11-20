package com.widen.util.td;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generates a plain-text report of the running JVM: a summary of problems found, host and runtime
 * details, memory, garbage collectors, deadlocked threads, and a stack trace for every live thread.
 *
 * <p>An instance is immutable and safe to share between threads. Each call to {@link #generate()}
 * reads the JVM again.
 */
public final class JvmThreadDump
{

    private final JvmSources jvm;
    private final List<String> mainArguments;
    private final Map<String, ?> customValues;
    private final Duration sampleWindow;
    private final boolean allFrames;

    private JvmThreadDump(JvmSources jvm, List<String> mainArguments, Map<String, ?> customValues,
            Duration sampleWindow, boolean allFrames) {
        this.jvm = jvm;
        this.mainArguments = mainArguments;
        this.customValues = customValues;
        this.sampleWindow = sampleWindow;
        this.allFrames = allFrames;
    }

    /**
     * Returns a dump with no main arguments and no custom values.
     */
    public static JvmThreadDump create() {
        return builder().build();
    }

    /**
     * Returns a builder with no main arguments and no custom values.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder holding this dump's settings, to make a changed copy.
     */
    public Builder toBuilder() {
        return new Builder()
                .jvmSources(jvm)
                .mainArguments(mainArguments)
                .customValues(customValues)
                .sampleWindow(sampleWindow)
                .allFrames(allFrames);
    }

    Duration sampleWindow() {
        return sampleWindow;
    }

    boolean allFrames() {
        return allFrames;
    }

    /**
     * Reads the JVM and returns the report. The call blocks for the sample window, one second by default, to measure
     * what the threads and collectors do meanwhile. An interrupt during the window ends the wait, leaves the thread's
     * interrupt flag set, and produces a dump that is not sampled: no window rates, no Hot Threads, and no CPU load.
     */
    public String generate() {
        JvmSnapshot snapshot = JvmSnapshot.read(jvm, sampleWindow);
        Findings findings = Findings.run(snapshot, Checks.ALL);
        List<Line> lines = new ArrayList<>();
        lines.addAll(new FindingsSection(findings, snapshot).lines());
        lines.addAll(new JvmInfoSection(jvm, snapshot, findings, mainArguments, customValues).lines());
        lines.addAll(new MemorySection(jvm, snapshot, findings).lines());
        lines.addAll(new GcSection(snapshot, findings).lines());
        lines.addAll(new DeadlockSection(snapshot).lines());
        lines.addAll(new ThreadSection(snapshot, findings, allFrames).lines());
        return Line.render(lines);
    }

    /**
     * Configures a {@link JvmThreadDump}. A builder is not thread-safe.
     */
    public static final class Builder
    {

        private JvmSources jvm = JvmSources.platform();
        private List<String> mainArguments = Collections.emptyList();
        private Map<String, ?> customValues = Collections.emptyMap();
        private Duration sampleWindow = Duration.ofSeconds(1);
        private boolean allFrames;

        private Builder() {
        }

        /**
         * Sets the program arguments to print, such as the {@code args} of {@code main}.
         *
         * @throws NullPointerException if the list is null
         */
        public Builder mainArguments(List<String> mainArguments) {
            this.mainArguments = Objects.requireNonNull(mainArguments, "mainArguments");
            return this;
        }

        /**
         * Sets extra values to print, one titled line each, in the map's iteration order. Each
         * {@link JvmThreadDump#generate()} formats a value with {@link String#valueOf(Object)}, so a null
         * value prints {@code null}.
         *
         * @throws NullPointerException if the map is null
         */
        public Builder customValues(Map<String, ?> customValues) {
            this.customValues = Objects.requireNonNull(customValues, "customValues");
            return this;
        }

        /**
         * Sets how long {@link JvmThreadDump#generate()} waits between its two readings of CPU time, allocation, and
         * garbage collection. Zero skips the wait and the figures that depend on it. The default is one second.
         *
         * @throws NullPointerException if the window is null
         * @throws IllegalArgumentException if the window is negative
         */
        public Builder sampleWindow(Duration sampleWindow) {
            Objects.requireNonNull(sampleWindow, "sampleWindow");
            if (sampleWindow.isNegative()) {
                throw new IllegalArgumentException("sampleWindow must not be negative: " + sampleWindow);
            }
            this.sampleWindow = sampleWindow;
            return this;
        }

        /**
         * Sets whether every stack frame prints. By default the dump hides reflection, generated proxy, Tapestry, and
         * servlet container frames and prints how many it hid; the top frame of each stack always prints.
         */
        public Builder allFrames(boolean allFrames) {
            this.allFrames = allFrames;
            return this;
        }

        Builder jvmSources(JvmSources jvm) {
            this.jvm = Objects.requireNonNull(jvm, "jvm");
            return this;
        }

        /**
         * Copies the main arguments and custom values, so later changes to the caller's collections
         * do not affect the dump.
         */
        public JvmThreadDump build() {
            return new JvmThreadDump(
                    jvm,
                    Collections.unmodifiableList(new ArrayList<>(mainArguments)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(customValues)),
                    sampleWindow,
                    allFrames);
        }

    }

}
