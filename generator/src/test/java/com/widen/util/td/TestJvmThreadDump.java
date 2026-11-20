package com.widen.util.td;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.widen.util.td.TestText.titled;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    static Stream<Arguments> invalidWindows() {
        return Stream.of(
                Arguments.of(null, NullPointerException.class),
                Arguments.of(Duration.ofMillis(-1), IllegalArgumentException.class));
    }

    @ParameterizedTest
    @MethodSource("invalidWindows")
    void rejectsANullOrNegativeWindow(Duration window, Class<? extends Throwable> expected) {
        assertThatThrownBy(() -> JvmThreadDump.builder().sampleWindow(window)).isInstanceOf(expected);
    }

    @Test
    void samplesForOneSecondByDefault() {
        FakeJvm jvm = new FakeJvm();

        JvmThreadDump.builder().jvmSources(jvm).build().generate();

        assertThat(jvm.nanoTime).isEqualTo(1_000_000_000L);
    }

    @Test
    void carriesItsSettingsIntoANewBuilder() {
        FakeJvm jvm = new FakeJvm();
        JvmThreadDump dump = JvmThreadDump.builder()
                .jvmSources(jvm)
                .mainArguments(Arrays.asList("--port", "8080"))
                .customValues(Collections.singletonMap("App", "cheese"))
                .sampleWindow(Duration.ofSeconds(5))
                .build();

        JvmThreadDump copy = dump.toBuilder().build();

        assertThat(copy.sampleWindow()).isEqualTo(Duration.ofSeconds(5));
        assertThat(copy.generate())
                .contains(titled("Main Arguments", "--port 8080"))
                .contains(titled("App", "cheese"))
                .isEqualTo(dump.generate());
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
