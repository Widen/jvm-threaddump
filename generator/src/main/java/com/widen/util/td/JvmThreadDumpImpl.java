package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.widen.util.td.Line.BlankLine;

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
