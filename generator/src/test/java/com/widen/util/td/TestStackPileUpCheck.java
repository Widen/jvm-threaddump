package com.widen.util.td;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;

public class TestStackPileUpCheck
{
    private static final StackTraceElement[] DB_STACK = {
            FakeThreads.frame("sun.nio.ch.NioSocketImpl", "read"),
            FakeThreads.frame("java.io.InputStream", "read"),
            FakeThreads.frame("$Dao_1a", "find"),
            FakeThreads.frame("org.postgresql.core.PGStream", "receive"),
            FakeThreads.frame("com.example.Dao", "find") };

    private static final StackTraceElement[] IDLE_JETTY_STACK = {
            FakeThreads.frame("sun.misc.Unsafe", "park"),
            FakeThreads.frame("org.eclipse.jetty.util.thread.QueuedThreadPool", "idleJobPoll"),
            FakeThreads.frame("java.lang.Thread", "run") };

    /** Adds {@code count} threads named {@code prefix-0} up, with ids from {@code firstId}. */
    private static void addThreads(FakeJvm jvm, String prefix, int count, long firstId, Thread.State state,
            StackTraceElement... frames) {
        for (int i = 0; i < count; i++) {
            jvm.addThread(prefix + "-" + i, firstId + i, state, frames);
        }
    }

    private static List<Finding> findingsOf(FakeJvm jvm) {
        return new StackPileUpCheck().findings(jvm.snapshot());
    }

    @ParameterizedTest
    @CsvSource({ "9,0", "10,1" })
    void flagsTenOrMoreThreadsInTheSameCall(int threads, int expectedFindings) {
        FakeJvm jvm = new FakeJvm();
        addThreads(jvm, "db", threads, 1000, Thread.State.RUNNABLE, DB_STACK);

        assertThat(findingsOf(jvm)).hasSize(expectedFindings);
    }

    @Test
    void printsTheSharedFramesAndFirstThreeNames() {
        FakeJvm jvm = new FakeJvm();
        addThreads(jvm, "db", 7, 1000, Thread.State.RUNNABLE, DB_STACK);
        for (int i = 7; i < 10; i++) {
            jvm.addThread("db-" + i, 1000 + i, Thread.State.TIMED_WAITING, DB_STACK);
        }

        List<Finding> findings = findingsOf(jvm);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).title).isEqualTo("Stack Pile-up");
        assertThat(findings.get(0).lines).containsExactly(
                "10 threads are in the same call (7 RUNNABLE, 3 TIMED_WAITING)",
                "    sun.nio.ch.NioSocketImpl.read(NioSocketImpl.java:1)",
                "    org.postgresql.core.PGStream.receive(PGStream.java:1)",
                "\"db-0\", \"db-1\", \"db-2\" and 7 more");
        assertThat(findings.get(0).marks).isEmpty();
    }

    @Test
    void printsOneFrameWhenTheTopFrameIsTheKey() {
        FakeJvm jvm = new FakeJvm();
        addThreads(jvm, "spin", 10, 1000, Thread.State.RUNNABLE,
                FakeThreads.frame("com.example.Job", "spin"), FakeThreads.frame("java.lang.Thread", "run"));

        assertThat(findingsOf(jvm).get(0).lines).containsExactly(
                "10 threads are in the same call (10 RUNNABLE)",
                "    com.example.Job.spin(Job.java:1)",
                "\"spin-0\", \"spin-1\", \"spin-2\" and 7 more");
    }

    @Test
    void groupsByTheLibraryUnderTheSameTopFrame() {
        FakeJvm jvm = new FakeJvm();
        addThreads(jvm, "pg", 5, 1000, Thread.State.RUNNABLE, DB_STACK);
        addThreads(jvm, "http", 5, 2000, Thread.State.RUNNABLE,
                FakeThreads.frame("sun.nio.ch.NioSocketImpl", "read"),
                FakeThreads.frame("org.apache.http.impl.io.SessionInputBufferImpl", "read"));

        assertThat(findingsOf(jvm)).isEmpty();
    }

    @Test
    void ignoresLineNumbersWhenGrouping() {
        FakeJvm jvm = new FakeJvm();
        for (int i = 0; i < 10; i++) {
            jvm.addThread("spin-" + i, 1000 + i, Thread.State.RUNNABLE,
                    new StackTraceElement("com.example.Job", "spin", "Job.java", 10 + i));
        }

        List<Finding> findings = findingsOf(jvm);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).lines.get(1)).isEqualTo("    com.example.Job.spin(Job.java:10)");
    }

    static Stream<Arguments> threadsThatAreNotPiledUp() {
        return Stream.of(
                arguments("idle Jetty workers", (Consumer<FakeJvm>) jvm ->
                        addThreads(jvm, "w", 10, 1000, Thread.State.WAITING, IDLE_JETTY_STACK)),
                arguments("an empty stack", (Consumer<FakeJvm>) jvm ->
                        addThreads(jvm, "w", 10, 1000, Thread.State.RUNNABLE)),
                arguments("behind a lock pile-up", (Consumer<FakeJvm>) jvm -> {
                    jvm.addThread("holder", 10, Thread.State.RUNNABLE, FakeThreads.frame("com.example.H", "work"));
                    for (int i = 0; i < 10; i++) {
                        jvm.addThreadWaitingOn("w-" + i, 1000 + i, Thread.State.BLOCKED, 10, null, DB_STACK);
                    }
                }),
                arguments("in and behind a deadlock", (Consumer<FakeJvm>) jvm -> {
                    jvm.addThreadWaitingOn("a", 1, Thread.State.BLOCKED, 2, null, DB_STACK);
                    jvm.addThreadWaitingOn("b", 2, Thread.State.BLOCKED, 1, null, DB_STACK);
                    for (int i = 0; i < 8; i++) {
                        jvm.addThreadWaitingOn("w-" + i, 1000 + i, Thread.State.BLOCKED, 1, null, DB_STACK);
                    }
                    when(jvm.threads.findDeadlockedThreads()).thenReturn(new long[] { 1, 2 });
                }),
                arguments("the generating thread", (Consumer<FakeJvm>) jvm -> {
                    addThreads(jvm, "w", 9, 1000, Thread.State.RUNNABLE, DB_STACK);
                    jvm.addThread("generator", Thread.currentThread().getId(), Thread.State.RUNNABLE, DB_STACK);
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("threadsThatAreNotPiledUp")
    void leavesOutThreadsThatAreNotPiledUp(String description, Consumer<FakeJvm> arrange) {
        FakeJvm jvm = new FakeJvm();
        arrange.accept(jvm);

        assertThat(findingsOf(jvm)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "WAITING,sun.misc.Unsafe java.lang.Thread,true",
            "TIMED_WAITING,sun.misc.Unsafe java.lang.Thread,true",
            "WAITING,javax.a jdk.b sun.c com.sun.d org.eclipse.jetty.e org.apache.tomcat.f org.apache.catalina.g io.netty.h com.zaxxer.hikari.i,true",
            "WAITING,,true",
            "RUNNABLE,,true",
            "RUNNABLE,sun.misc.Unsafe java.lang.Thread,false",
            "BLOCKED,sun.misc.Unsafe java.lang.Thread,false",
            "WAITING,sun.misc.Unsafe com.example.Dao java.lang.Thread,false" })
    void treatsAWaitingThreadWithOnlyLibraryFramesAsIdle(Thread.State state, String classNames, boolean expectedIdle) {
        FakeJvm jvm = new FakeJvm();
        String[] classes = classNames == null ? new String[0] : classNames.split(" ");
        StackTraceElement[] frames = new StackTraceElement[classes.length];
        for (int i = 0; i < classes.length; i++) {
            frames[i] = FakeThreads.frame(classes[i], "m");
        }
        jvm.addThread("t", 1000, state, frames);

        assertThat(StackPileUpCheck.idle(jvm.snapshot().threads.get(0))).isEqualTo(expectedIdle);
    }

    @Test
    void printsTheLargestGroupFirst() {
        FakeJvm jvm = new FakeJvm();
        addThreads(jvm, "a", 10, 1000, Thread.State.RUNNABLE, FakeThreads.frame("com.example.A", "spin"));
        addThreads(jvm, "b", 12, 2000, Thread.State.RUNNABLE, FakeThreads.frame("com.example.B", "spin"));

        assertThat(findingsOf(jvm)).extracting(finding -> finding.lines.get(0)).containsExactly(
                "12 threads are in the same call (12 RUNNABLE)",
                "10 threads are in the same call (10 RUNNABLE)");
    }
}
