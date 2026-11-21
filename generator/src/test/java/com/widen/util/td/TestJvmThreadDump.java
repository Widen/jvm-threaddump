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
        Findings findings = Findings.run(snapshot, Checks.all(emptyList()));
        lines.addAll(new FindingsSection(findings, snapshot).lines());
        lines.addAll(new JvmInfoSection(jvm, snapshot, findings, emptyList(), emptyMap()).lines());
        lines.addAll(new MemorySection(jvm, snapshot, findings).lines());
        lines.addAll(new GcSection(snapshot, findings).lines());
        lines.addAll(new DeadlockSection(snapshot).lines());
        lines.addAll(new ThreadSection(snapshot, findings, false).lines());

        assertThat(JvmThreadDump.builder().jvmSources(jvm).sampleWindow(Duration.ZERO).build().generate())
                .isEqualTo(Line.render(lines));
    }

    @Test
    void countsNineChecksWithAWindow() {
        FakeJvm jvm = new FakeJvm();
        jvm.enableCpuTime();
        jvm.unixOperatingSystem(412, 65_536);
        JvmThreadDump.Builder builder = JvmThreadDump.builder().jvmSources(jvm);

        assertThat(builder.sampleWindow(Duration.ofSeconds(1)).build().generate())
                .contains(titled("Findings", "None (9 checks, sampled over 1.0s)"));
        assertThat(builder.sampleWindow(Duration.ZERO).build().generate())
                .contains(titled("Findings", "None (8 checks, not sampled)"));
    }

    @Test
    void flagsAThrottledContainerAsATenthCheck() {
        FakeJvm jvm = new FakeJvm();
        jvm.enableCpuTime();
        jvm.unixOperatingSystem(412, 65_536);
        jvm.cgroupV2(100_000, 100, 0);
        FakeJvm later = new FakeJvm();
        later.cgroupV2(100_000, 110, 5);
        jvm.filesAfterPause.putAll(later.files);

        String dump = JvmThreadDump.builder().jvmSources(jvm).build().generate();

        assertThat(dump)
                .contains(titled("Findings", "1 of 10 checks flagged something (sampled over 1.0s)"))
                .contains(titled("CPU Throttled", "the kernel throttled this container in 5 periods (50%) of the last 1.0s"))
                .contains(titled("CPU Limit", "1.00 CPUs; throttled in 5 of 10 periods; 5 of 110 since the container started <<< CPU Throttled"));
        assertThat(dump.indexOf("CPU Saturated")).isLessThan(0);
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
                .allFrames(true)
                .build();

        JvmThreadDump copy = dump.toBuilder().build();

        assertThat(copy.sampleWindow()).isEqualTo(Duration.ofSeconds(5));
        assertThat(copy.allFrames()).isTrue();
        assertThat(copy.generate())
                .contains(titled("Main Arguments", "--port 8080"))
                .contains(titled("App", "cheese"))
                .isEqualTo(dump.generate());
    }

    @Test
    void leavesWaitingThreadsOfAnIdleLibraryOutOfStackPileUp() {
        FakeJvm jvm = new FakeJvm();
        for (int i = 0; i < 10; i++) {
            jvm.addThread("quartz-" + i, 1000 + i, Thread.State.TIMED_WAITING,
                    FakeThreads.frame("java.lang.Object", "wait"),
                    FakeThreads.frame("org.quartz.simpl.SimpleThreadPool$WorkerThread", "run"));
        }
        JvmThreadDump.Builder builder = JvmThreadDump.builder().jvmSources(jvm).sampleWindow(Duration.ZERO);

        assertThat(builder.build().generate()).contains("Stack Pile-up");
        JvmThreadDump dump = builder.idleLibraryPrefixes(Collections.singletonList("org.quartz.")).build();
        assertThat(dump.generate()).doesNotContain("Stack Pile-up");
        assertThat(dump.toBuilder().build().generate()).doesNotContain("Stack Pile-up");
    }

    @Test
    void hidesPlumbingFramesUnlessAskedForAllFrames() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("worker", 23, Thread.State.RUNNABLE,
                FakeThreads.frame("com.example.Dao", "find"),
                FakeThreads.frame("java.lang.reflect.Method", "invoke"));
        JvmThreadDump.Builder builder = JvmThreadDump.builder().jvmSources(jvm).sampleWindow(Duration.ZERO);

        assertThat(builder.build().generate())
                .contains("    ... 1 frame hidden")
                .doesNotContain("java.lang.reflect.Method.invoke");
        assertThat(builder.allFrames(true).build().generate())
                .contains("java.lang.reflect.Method.invoke")
                .doesNotContain("frame hidden")
                .doesNotContain("frames hidden");
    }

    @Test
    void filtersAFindingsFramesEvenWhenTheDumpPrintsAllFrames() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("h", 10, Thread.State.RUNNABLE,
                FakeThreads.frame("com.example.H", "work"),
                FakeThreads.frame("$H_1a", "work"),
                FakeThreads.frame("com.example.H", "run"));
        TestLockPileUpCheck.addWaiters(jvm, 6, 10);

        String dump = JvmThreadDump.builder().jvmSources(jvm).sampleWindow(Duration.ZERO).allFrames(true).build()
                .generate();
        int threadList = dump.indexOf("Thread Count");

        assertThat(dump.substring(threadList)).contains("$H_1a.work");
        assertThat(dump.substring(0, threadList)).contains("6 threads wait on a lock held by \"h\"")
                .doesNotContain("$H_1a.work");
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
