package com.widen.util.td;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.widen.util.td.TestText.continued;
import static com.widen.util.td.TestText.titled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class TestFindingsSection
{
    static Stream<Arguments> printsNoneWithTheCheckCountAndWindow() {
        return Stream.of(
                arguments(2, Duration.ZERO, "None (2 checks, not sampled)"),
                arguments(2, Duration.ofSeconds(1), "None (2 checks, sampled over 1.0s)"),
                arguments(1, Duration.ZERO, "None (1 check, not sampled)"));
    }

    @ParameterizedTest
    @MethodSource
    void printsNoneWithTheCheckCountAndWindow(int checkCount, Duration window, String expected) {
        List<Check> checks = new ArrayList<>();
        for (int i = 0; i < checkCount; i++) {
            checks.add(StubCheck.flagging());
        }
        JvmSnapshot snapshot = JvmSnapshot.read(new FakeJvm(), window);

        assertThat(TestText.render(new FindingsSection(Findings.run(snapshot, checks), snapshot).lines()))
                .containsExactly(titled("Findings", expected), "");
    }

    @Test
    void printsFindingsInCheckOrderUnderACount() {
        JvmSnapshot snapshot = JvmSnapshot.read(new FakeJvm(), Duration.ofSeconds(1));
        List<Check> checks = Arrays.asList(
                StubCheck.flagging(
                        new Finding("Alpha", Arrays.asList("first", "second"), Collections.emptyList()),
                        new Finding("Alpha", Collections.singletonList("another"), Collections.emptyList())),
                StubCheck.notApplying(),
                StubCheck.flagging(),
                StubCheck.flagging(new Finding("Delta", Collections.singletonList("only"), Collections.emptyList())));

        assertThat(TestText.render(new FindingsSection(Findings.run(snapshot, checks), snapshot).lines()))
                .containsExactly(
                        titled("Findings", "2 of 3 checks flagged something (sampled over 1.0s)"),
                        "",
                        titled("Alpha", "first"),
                        continued("second"),
                        "",
                        titled("Alpha", "another"),
                        "",
                        titled("Delta", "only"),
                        "");
    }

    @Test
    void usesTheSingularForOneCheck() {
        JvmSnapshot snapshot = new FakeJvm().snapshot();
        Findings findings = StubCheck.findingsOf(snapshot, StubCheck.finding("Alpha"));

        assertThat(TestText.render(new FindingsSection(findings, snapshot).lines()).get(0))
                .isEqualTo(titled("Findings", "1 of 1 check flagged something (not sampled)"));
    }

    @Test
    void printsPercentSignsAsWritten() {
        JvmSnapshot snapshot = new FakeJvm().snapshot();
        Findings findings = StubCheck.findingsOf(snapshot,
                new Finding("Alpha", Collections.singletonList("\"100%s\" waits for lock%d"),
                        Collections.emptyList()));

        assertThat(TestText.render(new FindingsSection(findings, snapshot).lines()))
                .contains(titled("Alpha", "\"100%s\" waits for lock%d"));
    }
}
