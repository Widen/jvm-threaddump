package com.widen.util.td;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static com.widen.util.td.TestText.titled;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

public class TestJvmThreadDump
{
    @Test
    void rendersSectionsInOrder() {
        FakeJvm jvm = new FakeJvm();
        List<Line> lines = new ArrayList<>();
        JvmSnapshot snapshot = jvm.snapshot();
        lines.addAll(new JvmInfoSection(jvm, snapshot, emptyList(), emptyMap()).lines());
        lines.addAll(new MemorySection(jvm, snapshot).lines());
        lines.addAll(new GcSection(snapshot).lines());
        lines.addAll(new DeadlockSection(snapshot).lines());
        lines.addAll(new ThreadSection(snapshot).lines());

        assertThat(JvmThreadDump.builder().jvmSources(jvm).build().generate())
                .isEqualTo(Line.render(lines));
    }

    @Test
    void returnsTheSameDumpOnRepeatCalls() {
        JvmThreadDump dump = JvmThreadDump.builder().jvmSources(new FakeJvm()).build();

        assertThat(dump.generate()).isEqualTo(dump.generate());
    }

    @Test
    void printsMainArgumentsAndCustomValuesInMapOrder() {
        Map<String, Object> customValues = new LinkedHashMap<>();
        customValues.put("Zeta", 1);
        customValues.put("Alpha", null);

        String dump = JvmThreadDump.builder()
                .jvmSources(new FakeJvm())
                .mainArguments(Arrays.asList("--port", "8080"))
                .customValues(customValues)
                .build()
                .generate();

        assertThat(dump).contains(titled("Main Arguments", "--port 8080"));
        assertThat(dump).containsSubsequence(titled("Zeta", "1"), titled("Alpha", "null"));
    }

    @Test
    void ignoresChangesToTheCallersCollectionsAfterBuild() {
        List<String> mainArguments = new ArrayList<>(Arrays.asList("--port", "8080"));
        Map<String, Object> customValues = new LinkedHashMap<>();
        customValues.put("App", "cheese");
        JvmThreadDump dump = JvmThreadDump.builder()
                .jvmSources(new FakeJvm())
                .mainArguments(mainArguments)
                .customValues(customValues)
                .build();
        String before = dump.generate();

        mainArguments.add("--debug");
        customValues.put("Extra", "value");

        assertThat(dump.generate()).isEqualTo(before);
    }

    @Test
    void acceptsUnmodifiableCustomValues() {
        String dump = JvmThreadDump.builder()
                .jvmSources(new FakeJvm())
                .customValues(Collections.singletonMap("App", "cheese"))
                .build()
                .generate();

        assertThat(dump).contains(titled("App", "cheese"));
    }
}
