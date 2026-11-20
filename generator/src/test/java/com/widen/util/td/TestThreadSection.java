package com.widen.util.td;

import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static com.widen.util.td.TestText.titled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;

public class TestThreadSection
{
    private static final String HEADER_PREFIX = "group=test-group cpu=-1 block_cnt=0 wait_cnt=0 ";

    @Test
    void printsCountHeaderAndFrames() {
        Thread thread = FakeThreads.thread("worker-1", 23);
        ThreadInfo info = FakeThreads.info(23, Thread.State.WAITING,
                new StackTraceElement("com.example.Worker", "run", "Worker.java", 42),
                new StackTraceElement("java.lang.Thread", "run", "Thread.java", 750));
        when(info.getBlockedCount()).thenReturn(1L);
        when(info.getWaitedCount()).thenReturn(2L);
        when(info.getLockName()).thenReturn("java.util.concurrent.locks.ReentrantLock$NonfairSync@1b6d3586");
        when(info.getLockOwnerId()).thenReturn(31L);

        assertThat(TestText.render(lines(thread, info)))
                .containsExactly(
                        titled("Thread Count", "1 (1 WAITING)"),
                        "",
                        "\"worker-1\" priority=5 id=0x17 group=test-group cpu=-1 block_cnt=1 wait_cnt=2 WAITING"
                                + " (waiting on 0x1f for java.util.concurrent.locks.ReentrantLock$NonfairSync@1b6d3586)",
                        "    com.example.Worker.run(Worker.java:42)",
                        "    java.lang.Thread.run(Thread.java:750)",
                        "");
    }

    @ParameterizedTest
    @MethodSource
    void formatsThreadHeaders(Thread thread, ThreadInfo info, String header) {
        assertThat(TestText.render(lines(thread, info))).contains(header);
    }

    static Stream<Arguments> formatsThreadHeaders() {
        Thread timer = FakeThreads.thread("pool-timer", 40);
        timer.setDaemon(true);
        timer.setPriority(8);
        ThreadInfo timerInfo = FakeThreads.info(40, Thread.State.TIMED_WAITING);
        when(timerInfo.getLockName()).thenReturn("java.lang.Object@4554617c");

        ThreadInfo mainInfo = FakeThreads.info(1, Thread.State.RUNNABLE);

        ThreadInfo workerInfo = FakeThreads.info(2, Thread.State.RUNNABLE);

        ThreadInfo blockedInfo = FakeThreads.info(3, Thread.State.BLOCKED);
        when(blockedInfo.getLockName()).thenReturn("java.lang.Object@1");
        when(blockedInfo.getLockOwnerId()).thenReturn(4L);

        ThreadInfo ownerOnlyInfo = FakeThreads.info(5, Thread.State.BLOCKED);
        when(ownerOnlyInfo.getLockOwnerId()).thenReturn(6L);

        return Stream.of(
                arguments(timer, timerInfo, "\"pool-timer\" daemon priority=8 id=0x28 " + HEADER_PREFIX
                        + "TIMED_WAITING (waiting for java.lang.Object@4554617c)"),
                arguments(FakeThreads.thread("main", 1, "custom text"), mainInfo,
                        "\"main\" priority=5 id=0x1 " + HEADER_PREFIX + "RUNNABLE (custom text)"),
                arguments(FakeThreads.thread("worker", 2, "worker"), workerInfo,
                        "\"worker\" priority=5 id=0x2 " + HEADER_PREFIX + "RUNNABLE"),
                arguments(FakeThreads.thread("blocked", 3, "busy"), blockedInfo,
                        "\"blocked\" priority=5 id=0x3 " + HEADER_PREFIX
                                + "BLOCKED (waiting on 0x4 for java.lang.Object@1) (busy)"),
                arguments(FakeThreads.thread("owner-only", 5), ownerOnlyInfo,
                        "\"owner-only\" priority=5 id=0x5 " + HEADER_PREFIX + "BLOCKED (waiting on 0x6)"));
    }

    @Test
    void ordersThreadsByNameIgnoringCaseThenNameThenId() {
        FakeJvm jvm = new FakeJvm();
        long[] ids = {23, 3, 12, 2, 5, 6, 7, 9, 8};
        String[] names = {"worker-1", "worker-12", "Worker", "worker1", "b", "A", "a", "pool", "pool"};
        for (int i = 0; i < names.length; i++) {
            jvm.addThread(names[i], ids[i], Thread.State.RUNNABLE);
        }

        List<String> text = TestText.render(new ThreadSection(jvm.snapshot(), false).lines());

        assertThat(text.get(0)).isEqualTo(titled("Thread Count", "9 (9 RUNNABLE)"));
        assertThat(headersOf(text)).containsExactly(
                header("A", 6), header("a", 7), header("b", 5), header("pool", 8), header("pool", 9),
                header("Worker", 12), header("worker-1", 23), header("worker-12", 3), header("worker1", 2));
    }

    @Test
    void printsOnlyThreadsInBothSnapshots() {
        FakeJvm jvm = new FakeJvm();
        jvm.liveThreads.add(FakeThreads.thread("gone", 50));
        jvm.threadInfos.add(FakeThreads.info(60, Thread.State.RUNNABLE));
        jvm.addThread("kept", 61, Thread.State.RUNNABLE);

        List<String> text = TestText.render(new ThreadSection(jvm.snapshot(), false).lines());

        assertThat(text.get(0)).isEqualTo(titled("Thread Count", "1 (1 RUNNABLE)"));
        assertThat(headersOf(text)).containsExactly(header("kept", 61));
    }

    @Test
    void hidesPlumbingFramesAndCountsThem() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("worker", 23, Thread.State.RUNNABLE, plumbingStack());

        List<String> text = TestText.render(new ThreadSection(jvm.snapshot(), false).lines());

        assertThat(text.subList(2, text.size())).containsExactly(
                header("worker", 23),
                "    com.example.Dao.find(Dao.java:1)",
                "    com.example.Api.get(Api.java:1)",
                "    java.lang.Thread.run(Thread.java:1)",
                "    ... 3 frames hidden",
                "");
    }

    @Test
    void countsOneHiddenFrameInTheSingular() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("worker", 23, Thread.State.RUNNABLE,
                FakeThreads.frame("com.example.Dao", "find"),
                FakeThreads.frame("java.lang.reflect.Method", "invoke"));

        assertThat(TestText.render(new ThreadSection(jvm.snapshot(), false).lines()))
                .contains("    ... 1 frame hidden");
    }

    @Test
    void printsEveryFrameWhenAskedForAllFrames() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("worker", 23, Thread.State.RUNNABLE, plumbingStack());

        List<String> text = TestText.render(new ThreadSection(jvm.snapshot(), true).lines());

        assertThat(text.subList(2, text.size())).containsExactly(
                header("worker", 23),
                "    com.example.Dao.find(Dao.java:1)",
                "    $Dao_1a.find($Dao_1a.java:1)",
                "    java.lang.reflect.Method.invoke(Method.java:1)",
                "    com.example.Api.get(Api.java:1)",
                "    org.eclipse.jetty.server.Server.handle(Server.java:1)",
                "    java.lang.Thread.run(Thread.java:1)",
                "");
    }

    @Test
    void countsThreadsByState() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("a", 1, Thread.State.WAITING);
        jvm.addThread("b", 2, Thread.State.RUNNABLE);
        jvm.addThread("c", 3, Thread.State.WAITING);
        jvm.addThread("d", 4, Thread.State.BLOCKED);
        jvm.addThread("e", 5, Thread.State.RUNNABLE);
        jvm.addThread("f", 6, Thread.State.WAITING);

        assertThat(TestText.render(new ThreadSection(jvm.snapshot(), false).lines()).get(0))
                .isEqualTo(titled("Thread Count", "6 (2 RUNNABLE, 1 BLOCKED, 3 WAITING)"));
        assertThat(TestText.render(new ThreadSection(new FakeJvm().snapshot(), false).lines()).get(0))
                .isEqualTo(titled("Thread Count", "0"));
    }

    private static StackTraceElement[] plumbingStack() {
        return new StackTraceElement[] {
                FakeThreads.frame("com.example.Dao", "find"),
                FakeThreads.frame("$Dao_1a", "find"),
                FakeThreads.frame("java.lang.reflect.Method", "invoke"),
                FakeThreads.frame("com.example.Api", "get"),
                FakeThreads.frame("org.eclipse.jetty.server.Server", "handle"),
                FakeThreads.frame("java.lang.Thread", "run")};
    }

    @Test
    void printsDeadForAThreadThatEndedAfterTheSnapshot() throws InterruptedException {
        Thread thread = FakeThreads.thread("finished", 70);
        thread.start();
        thread.join();

        assertThat(TestText.render(lines(thread, FakeThreads.info(70, Thread.State.TERMINATED))))
                .contains("\"finished\" priority=5 id=0x46 group=-DEAD- cpu=-1 block_cnt=0 wait_cnt=0 TERMINATED");
    }

    @Test
    void printsCpuTimeWhenEnabled() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("worker-1", 23, Thread.State.RUNNABLE);
        when(jvm.threads.isThreadCpuTimeSupported()).thenReturn(true);
        when(jvm.threads.isThreadCpuTimeEnabled()).thenReturn(true);
        when(jvm.threads.getThreadCpuTime(23)).thenReturn(1_500_000_000L);

        assertThat(TestText.render(new ThreadSection(jvm.snapshot(), false).lines()))
                .anyMatch(line -> line.contains(" cpu=1500ms "));
    }

    @Test
    void keepsTheNameReadBeforeSortingWhenAThreadRenamesItself() {
        FakeJvm jvm = new FakeJvm();
        jvm.addThread("before", 23, Thread.State.RUNNABLE);
        Thread thread = jvm.liveThreads.iterator().next();
        when(jvm.threads.isThreadCpuTimeSupported()).thenReturn(true);
        when(jvm.threads.isThreadCpuTimeEnabled()).thenReturn(true);
        when(jvm.threads.getThreadCpuTime(23)).thenAnswer(invocation -> {
            thread.setName("after");
            return 0L;
        });

        List<String> text = TestText.render(new ThreadSection(jvm.snapshot(), false).lines());

        assertThat(headersOf(text)).singleElement().satisfies(header -> {
            assertThat(header).startsWith("\"before\"");
            assertThat(header).doesNotContain("(after)");
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"-1|-1", "0|0ms", "9999999999|9999ms", "10000000000|10s"})
    void formatsCpuTime(long nanos, String expected) {
        assertThat(ThreadSection.formatCpuTime(nanos)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource
    void formatsFrames(StackTraceElement frame, String expected) {
        assertThat(ThreadSection.formatFrame(frame)).isEqualTo(expected);
    }

    static Stream<Arguments> formatsFrames() {
        return Stream.of(
                arguments(new StackTraceElement("com.example.Worker", "run", "Worker.java", 42),
                        "    com.example.Worker.run(Worker.java:42)"),
                arguments(new StackTraceElement("com.example.Worker", "run", "Worker.java", -1),
                        "    com.example.Worker.run(Worker.java)"),
                arguments(new StackTraceElement("java.lang.Object", "wait", null, -2),
                        "    java.lang.Object.wait(Native Method)"),
                arguments(new StackTraceElement("com.example.Gen", "call", null, -1),
                        "    com.example.Gen.call(Unknown Source)"));
    }

    private static List<Line> lines(Thread thread, ThreadInfo info) {
        FakeJvm jvm = new FakeJvm();
        jvm.liveThreads.add(thread);
        jvm.threadInfos.add(info);
        return new ThreadSection(jvm.snapshot(), false).lines();
    }

    private static List<String> headersOf(List<String> text) {
        return text.stream().filter(line -> line.startsWith("\"")).collect(Collectors.toList());
    }

    private static String header(String name, long id) {
        return String.format("\"%s\" priority=5 id=0x%x %sRUNNABLE", name, id, HEADER_PREFIX);
    }
}
