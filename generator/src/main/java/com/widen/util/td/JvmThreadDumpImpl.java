package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        lines.addAll(new MemorySection(jvm).lines());
        lines.addAll(new GcSection(jvm).lines());
        lines.addAll(new DeadlockSection(jvm).lines());
        lines.addAll(new ThreadSection(jvm).lines());

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

}
