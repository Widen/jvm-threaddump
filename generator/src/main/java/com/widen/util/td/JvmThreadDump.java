package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generates a plain-text report of the running JVM: host and runtime details, memory, garbage
 * collectors, deadlocked threads, and a stack trace for every live thread.
 *
 * <p>An instance is immutable and safe to share between threads. Each call to {@link #generate()}
 * reads the JVM again.
 */
public final class JvmThreadDump
{

    private final JvmSources jvm;
    private final List<String> mainArguments;
    private final Map<String, ?> customValues;

    private JvmThreadDump(JvmSources jvm, List<String> mainArguments, Map<String, ?> customValues) {
        this.jvm = jvm;
        this.mainArguments = mainArguments;
        this.customValues = customValues;
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
     * Reads the JVM now and returns the report.
     */
    public String generate() {
        List<Line> lines = new ArrayList<>();
        lines.addAll(new JvmInfoSection(jvm, mainArguments, customValues).lines());
        lines.addAll(new MemorySection(jvm).lines());
        lines.addAll(new GcSection(jvm).lines());
        lines.addAll(new DeadlockSection(jvm).lines());
        lines.addAll(new ThreadSection(jvm).lines());
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
                    Collections.unmodifiableMap(new LinkedHashMap<>(customValues)));
        }

    }

}
