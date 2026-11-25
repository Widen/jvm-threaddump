# Frame Filter and Stack Grouping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trim client library pipelines and executor plumbing from printed
stacks, and print a stack once for threads that share it.

**Architecture:** `StackFilter.hidden` gains the executor, Netty wrapper, and
accessor rules. `StackFilter.kept` then drops repeated calls and collapses runs
of one library to their ends. A new public enum, `ThreadGrouping`, set on
`JvmThreadDump.Builder` and by the servlet's `group` parameter, tells
`ThreadSection` how to group threads with equal raw stacks.

**Tech Stack:** Gradle 9.9.2 (Kotlin DSL), Java 17 toolchain with all code
compiled at `options.release = 8`, tests run by `test` (Java 17) and
`testOnJava8`, JUnit Jupiter 5.14.4, AssertJ 3.27.7, Mockito 4.11.0.

**Spec:** `docs/superpowers/specs/2026-11-24-frame-filter-design.md`
**Issue:** <https://github.com/Widen/jvm-threaddump/issues/11>

## Global Constraints

- All code, tests included, compiles with `options.release = 8`: no Java 9+ API
  (no `List.of`, `Map.of`, `Set.of`, `var`).
- Generator classes are in
  `generator/src/{main,test}/java/com/widen/util/td/` and servlet classes in
  `servlet/src/{main,test}/java/com/widen/util/td/`, all in package
  `com.widen.util.td`. Paths below without a directory are in the generator.
- Match the surrounding style: class brace on its own line, package-private
  classes and fields, `Locale.US` in every `String.format`, a Javadoc only where
  it says what the code does not. Public API (`ThreadGrouping`, the builder
  method) gets Javadoc.
- The top frame of a stack always prints, whatever the rules say.
- Run one test class with
  `mise exec -- ./gradlew :jvm-threaddump-generator:test --tests com.widen.util.td.CLASS -q --console=plain > SCRATCH/gradle.txt 2>&1`
  (use `:jvm-threaddump-servlet:test` for servlet classes), then read the file:
  no output means the tests passed. Before each commit, run
  `mise exec -- ./gradlew :jvm-threaddump-generator:test :jvm-threaddump-generator:testOnJava8 :jvm-threaddump-servlet:test -q --console=plain`
  the same way. Each takes under 30 seconds.
- Never run Spotless, `check`, or `build`.
- Commit messages are conventional commits with no trailers.
- After editing `README.md`, run
  `mise exec rumdl -- rumdl fmt --flavor gfm --config 'MD013.reflow = true' README.md`
  and `mise exec rumdl -- rumdl check --flavor gfm README.md`.

## Review Focus

1. A library run that starts at the top frame, such as a Netty selector thread
   whose top frame is `sun.nio.ch.EPollArrayWrapper.epollWait`. The top frame
   stays and is the run's first end. Task 2, `collapsesARunToItsEnds`.
2. A hidden frame, such as a `$$Lambda` frame inside the HttpClient execution
   chain, does not split a library run or a repeat. Runs and repeats are judged
   on kept frames only. Task 2, `aHiddenFrameSplitsNeitherARunNorARepeat`.
3. A thread waiting for a pooled connection or sleeping in an SDK retry keeps
   the library frame it waits in, and `Stack Pile-up` keys on the same frame as
   before. Task 2, `keepsTheFrameStackPileUpKeysOn`.
4. Two threads whose kept frames match but whose raw stacks differ, by a hidden
   frame or by a line number, print separately. Task 3,
   `groupsOnlyEqualRawStacks`.
5. The members of a group carry different finding markers and states. Each
   header keeps its own. Task 3, `keepsEachMembersHeaderAndMarker`.

---

### Task 1: Hide executor, Netty wrapper, and accessor frames

**Files:**

- Modify: `StackFilter.java`, `TestStackFilter.java`, `TestThreadSection.java`
  (`printsCountHeaderAndFrames`, `hidesPlumbingFramesAndCountsThem`), and any
  other test whose expected frames include the newly hidden ones

**Interfaces:**

- Produces: `StackFilter.hidden(StackTraceElement frame)` returns true for the
  new rules as well as the current ones. No signature changes.

- [ ] **Step 1: Write the failing test** in `TestStackFilter`:

```java
@ParameterizedTest
@CsvSource(delimiter = '|', value = {
        "java.util.concurrent.locks.LockSupport|park|true",
        "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject|await|true",
        "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode|block|true",
        "java.util.concurrent.locks.AbstractQueuedLongSynchronizer|acquire|true",
        "java.util.concurrent.ThreadPoolExecutor|runWorker|true",
        "java.util.concurrent.ThreadPoolExecutor$Worker|run|true",
        "java.util.concurrent.FutureTask|run|true",
        "java.util.concurrent.FutureTask|runAndReset|true",
        "java.util.concurrent.Executors$RunnableAdapter|call|true",
        "java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask|run|true",
        "java.util.concurrent.ForkJoinWorkerThread|run|true",
        "java.util.concurrent.ForkJoinPool|runWorker|true",
        "java.util.concurrent.ForkJoinPool|managedBlock|true",
        "java.util.concurrent.ForkJoinPool|unmanagedBlock|true",
        "java.lang.Thread|run|true",
        "java.lang.Thread|runWith|true",
        "io.netty.util.concurrent.SingleThreadEventExecutor$4|run|true",
        "io.netty.util.internal.ThreadExecutorMap$2|run|true",
        "io.netty.util.concurrent.FastThreadLocalRunnable|run|true",
        "software.amazon.awssdk.http.apache5.Apache5HttpClient|access$700|true",
        "com.example.Outer|access$000|true",
        "java.util.concurrent.ThreadPoolExecutor|getTask|false",
        "java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue|take|false",
        "java.util.concurrent.LinkedBlockingQueue|take|false",
        "java.util.concurrent.locks.ReentrantLock|lock|false",
        "java.lang.Thread|sleep|false",
        "io.netty.channel.nio.NioEventLoop|run|false",
        "io.netty.util.concurrent.SingleThreadEventExecutor|runAllTasks|false",
        "com.example.Outer|accessible|false",
        "com.example.Outer|access$|false",
        "com.example.Outer|lambda$run$0|false"})
void hidesExecutorNettyAndAccessorPlumbing(String className, String method, boolean hidden) {
    assertThat(StackFilter.hidden(FakeThreads.frame(className, method))).isEqualTo(hidden);
}
```

In `hidesPlumbingFrames`, replace the row `"java.lang.Thread|false"` with
`"java.lang.Object|false"`, since `Thread.run` is now hidden.

- [ ] **Step 2: Run `TestStackFilter` and confirm the new test fails** on the
  `true` rows.

- [ ] **Step 3: Extend `StackFilter.hidden`.**
  - Add to `HIDDEN_PREFIXES`: `java.util.concurrent.locks.LockSupport`,
    `java.util.concurrent.locks.AbstractQueuedSynchronizer`,
    `java.util.concurrent.locks.AbstractQueuedLongSynchronizer`,
    `io.netty.util.concurrent.SingleThreadEventExecutor$`,
    `io.netty.util.internal.ThreadExecutorMap$`,
    `io.netty.util.concurrent.FastThreadLocalRunnable`.
  - Add a `Set<String>` of `class#method` keys, in the style of
    `StackPileUpCheck.NATIVE_WAITS`, for the method rows of the spec's executor
    table: `ThreadPoolExecutor#runWorker`, `ThreadPoolExecutor$Worker#run`,
    `FutureTask#run`, `FutureTask#runAndReset`, `Executors$RunnableAdapter#call`,
    `ScheduledThreadPoolExecutor$ScheduledFutureTask#run`,
    `ForkJoinWorkerThread#run`, `ForkJoinPool#runWorker`,
    `ForkJoinPool#managedBlock`, `ForkJoinPool#unmanagedBlock` (all in
    `java.util.concurrent.`), `java.lang.Thread#run`, `java.lang.Thread#runWith`.
  - Hide a method named `access$` followed by one or more digits.
  - Update the class Javadoc to name executor plumbing, Netty thread wrappers,
    and accessors.

- [ ] **Step 4: Run `TestStackFilter` and confirm it passes.**

- [ ] **Step 5: Run the generator suites.** Fix each test that expected a
  `Thread.run` or executor frame by changing only its expected frames and
  hidden count. `TestThreadSection.printsCountHeaderAndFrames` now ends with
  `    ... 1 frame hidden`, and `hidesPlumbingFramesAndCountsThem` drops
  `java.lang.Thread.run` and counts `4 frames hidden`. All suites pass.

- [ ] **Step 6: Commit** with
  `feat: hide executor, netty wrapper, and accessor frames`.

### Task 2: Drop repeated calls and collapse library runs

**Files:**

- Modify: `StackFilter.java`, `TestStackFilter.java`,
  `JvmThreadDump.java` (`Builder.allFrames` Javadoc), `README.md` ("The frame
  filter")

**Interfaces:**

- Consumes: `StackFilter.hidden` from Task 1.
- Produces: `StackFilter.kept(StackTraceElement[] stack)` returns the top frame,
  then the frames that survive the hidden, repeat, and library-run rules, in
  stack order. `firstKeptOutsideJdk` keeps calling it. No signature changes.

- [ ] **Step 1: Write the failing tests** in `TestStackFilter`. Build stacks
  with `FakeThreads.frame`, or `new StackTraceElement(...)` where the line
  matters, and compare `kept(stack)` with `containsExactly`:
  - `dropsACallThatRepeatsTheOneAbove`: `Object.wait` (top), `Object.wait` at
    another line, `com.example.Main.main` keeps the first and last.
  - `collapsesARunToItsEnds`: `sun.nio.ch.EPollArrayWrapper.epollWait` (top),
    `sun.nio.ch.EPollArrayWrapper.poll`, `sun.nio.ch.SelectorImpl.select`,
    `io.netty.channel.nio.SelectedSelectionKeySetSelector.select`,
    `io.netty.channel.nio.NioEventLoop.select`,
    `io.netty.channel.nio.NioEventLoop.run`,
    `io.netty.util.concurrent.SingleThreadEventExecutor$4.run`,
    `io.netty.util.internal.ThreadExecutorMap$2.run`, `java.lang.Thread.run`
    keeps `epollWait`, `SelectorImpl.select`,
    `SelectedSelectionKeySetSelector.select`, `NioEventLoop.run`.
  - `aHiddenFrameSplitsNeitherARunNorARepeat`:
    `org.apache.hc.client5.A.x` (top), `org.apache.hc.client5.B.y`,
    `org.apache.hc.client5.B$$Lambda$9.proceed`, `org.apache.hc.client5.B.y`,
    `org.apache.hc.client5.C.z`, `com.example.App.run` keeps `A.x`, `C.z`,
    `App.run`.
  - `keepsBothFramesOfATwoFrameRun`: `com.amazonaws.A.a` (top),
    `com.amazonaws.B.b`, `com.example.App.run` keeps all three.
  - `trimsAnSqsReceive`: the spec's SQS thread in short form, one or more frames
    per class group of the original, keeps exactly the 11 frames of the spec's
    sample (`socketRead0`, `SocketInputStream.read`,
    `SSLSocketInputRecord.read`, `SSLSocketImpl$AppInputStream.read`,
    `SessionInputBufferImpl.fillBuffer`, `HttpClient.executeOpen`,
    `Apache5SdkHttpClient.executeOpen`, `DefaultSqsClient.receiveMessage`, and
    the three `com.widen.mica` frames). Include `Apache5HttpClient.access$700`,
    two `SocketInputStream.read` frames, and a `$$Lambda` frame in the
    HttpClient run.
  - `trimsAnIdleJava17PoolThreadToThreeFrames`:
    `jdk.internal.misc.Unsafe.park` (top), `LockSupport.park`,
    `AbstractQueuedSynchronizer$ConditionNode.block`,
    `ForkJoinPool.unmanagedBlock`, `ForkJoinPool.managedBlock`,
    `AbstractQueuedSynchronizer$ConditionObject.await`,
    `LinkedBlockingQueue.take`, `ThreadPoolExecutor.getTask`,
    `ThreadPoolExecutor.runWorker`, `ThreadPoolExecutor$Worker.run`,
    `Thread.run` keeps `Unsafe.park`, `LinkedBlockingQueue.take`,
    `ThreadPoolExecutor.getTask`.
  - `keepsTheFrameStackPileUpKeysOn`: `firstKeptOutsideJdk` returns
    `SessionInputBufferImpl.fillBuffer` for the SQS stack above, and
    `org.apache.hc.core5.pool.StrictConnPool.lease` for `Unsafe.park` (top),
    `LockSupport.parkNanos`, `AbstractQueuedSynchronizer$ConditionObject.awaitNanos`,
    `StrictConnPool.lease`, `PoolingHttpClientConnectionManager.lease` (in
    `org.apache.hc.client5.http.impl.io.`), `com.example.App.call`.

- [ ] **Step 2: Run `TestStackFilter` and confirm the new tests fail.**

- [ ] **Step 3: Implement the rules in `StackFilter.kept`.** Add the spec's ten
  collapsed prefixes as a list. One pass applies the hidden rule, then drops a
  frame whose class and method equal the last kept frame's. A second pass over
  that list finds each run of two or more consecutive frames that start with
  the same collapsed prefix and keeps only its first and last frame. The top
  frame is index 0 of the stack and is always kept.

- [ ] **Step 4: Run `TestStackFilter` and confirm it passes.**

- [ ] **Step 5: Update the docs.**
  - `Builder.allFrames` Javadoc: name executor plumbing, accessors, repeated
    calls, and the middle of client library calls among what the filter hides.
  - README "The frame filter": add the executor, Netty wrapper, and accessor
    rows to the table, a paragraph on repeated calls, and a paragraph on
    library runs with the spec's prefix table. Replace the sample output so it
    no longer ends in `java.lang.Thread.run` and its hidden count matches. Keep
    "The rules are fixed. A consumer cannot add its own."

- [ ] **Step 6: Run the generator and servlet suites.** Fix any test whose
  expected frames changed, as in Task 1. All suites pass.

- [ ] **Step 7: Commit** with
  `feat: drop repeated calls and collapse library runs in stacks`.

### Task 3: Group threads that share a stack

**Files:**

- Create: `ThreadGrouping.java`
- Modify: `ThreadSection.java`, `JvmThreadDump.java`, `TestThreadSection.java`,
  `TestJvmThreadDump.java` (`rendersSectionsInOrder` and a builder test),
  `README.md`

**Interfaces:**

- Consumes: `StackFilter.kept` from Task 2.
- Produces:
  - `public enum ThreadGrouping { SAME_STACK, NEIGHBORS, NONE }`, each value
    with a Javadoc line from the spec.
  - `ThreadSection(JvmSnapshot snapshot, Findings findings, boolean allFrames, ThreadGrouping grouping)`
    replacing the three-argument constructor. Existing tests that build a
    `ThreadSection` pass `ThreadGrouping.SAME_STACK`.
  - `public Builder threadGrouping(ThreadGrouping threadGrouping)` on
    `JvmThreadDump.Builder`, default `SAME_STACK`, throwing
    `NullPointerException` with the message `threadGrouping` for null.
    `toBuilder()` copies it, and the package-private `ThreadGrouping threadGrouping()`
    on `JvmThreadDump` returns it, as `allFrames()` does.

- [ ] **Step 1: Write the failing tests** in `TestThreadSection`. Give the
  threads RUNNABLE stacks with an application frame so no rule hides them:
  - `gathersEveryThreadWithTheSameStackUnderTheFirst`: threads `a` (stack S),
    `b` (stack T), `c` (stack S) under `SAME_STACK` print
    `header(a)`, `header(c)`, S's frames, `""`, `header(b)`, T's frames, `""`.
  - `sharesAStackOnlyBetweenNeighbors`: the same threads under `NEIGHBORS`
    print three separate blocks in name order; adding `a2` with stack S prints
    `header(a)`, `header(a2)`, S's frames as one block before `b`.
  - `printsEveryStackWithoutGrouping`: the same threads under `NONE` print three
    blocks.
  - `groupsOnlyEqualRawStacks`: two threads whose stacks differ only by a line
    number, and two whose stacks differ only by a hidden `$$Lambda` frame,
    print four blocks under `SAME_STACK`.
  - `neverGroupsEmptyStacks`: two threads with no frames print two headers each
    followed by `""`.
  - `keepsEachMembersHeaderAndMarker`: a group of two where one thread carries a
    `StubCheck` finding marker and is `WAITING` and the other is `RUNNABLE`
    prints both headers as they are, the marker on its own thread's header
    only, and `Thread Count` reads `2 (1 RUNNABLE, 1 WAITING)`.
  - `printsTheSharedHiddenCountOnce`: a group of two whose stack hides frames
    prints one `... N frames hidden` line.

  In `TestJvmThreadDump`, add `groupsBySameStackUnlessToldOtherwise`: two fake
  threads with the same stack print one stack by default and two with
  `threadGrouping(ThreadGrouping.NONE)`; `toBuilder()` keeps `NONE`; and
  `threadGrouping(null)` throws `NullPointerException`.

- [ ] **Step 2: Run `TestThreadSection` and `TestJvmThreadDump` and confirm the
  new tests fail** (compile errors count).

- [ ] **Step 3: Implement.** Create `ThreadGrouping`. In `ThreadSection`, build
  groups from `snapshot.threads`, which is in name order: `SAME_STACK` keys a
  `LinkedHashMap` on `Arrays.asList(stack)`, `NEIGHBORS` starts a new group
  whenever the stack differs from the previous thread's, and `NONE` makes one
  group per thread. A thread with an empty stack is always a group of its own.
  Print each group's headers with their markers, then the shared frames and
  hidden-count line from the first member, then the blank line. Wire the
  builder field, `toBuilder()`, the getter, and the `generate()` call.

- [ ] **Step 4: Run both classes and confirm they pass.**

- [ ] **Step 5: Update the README.**
  - Add a section after "The frame filter" on repeated stacks: the three
    layouts, the default, that stacks must match exactly, and the spec's
    `MariaDb-timeout` sample.
  - "Customization": add `threadGrouping(ThreadGrouping)` with its default, and
    name the grouping among the settings `toBuilder()` holds.
  - The example dump near the end: update its thread section to the new rules
    (the repeated `ReferenceQueue.remove`, the hidden counts, and any trailing
    `Thread.run`). Keep its note on what it trims accurate.

- [ ] **Step 6: Run the generator and servlet suites.** All pass.

- [ ] **Step 7: Commit** with `feat: print one stack for threads that share it`.

### Task 4: Choose the grouping per request

**Files:**

- Modify: `servlet/src/main/java/com/widen/util/td/ThreadDumpServlet.java`,
  `servlet/src/test/java/com/widen/util/td/TestThreadDumpServlet.java`,
  `README.md`

**Interfaces:**

- Consumes: `JvmThreadDump.Builder.threadGrouping` and
  `JvmThreadDump.threadGrouping()` from Task 3.
- Produces:
  `static JvmThreadDump forRequest(JvmThreadDump dump, String window, String frames, String group)`,
  replacing the three-argument form.

- [ ] **Step 1: Write the failing tests** in `TestThreadDumpServlet`:
  - `buildsADumpWithTheRequestedGrouping` (parameterized):
    `same-stack`, `neighbors`, and `none` map to `SAME_STACK`, `NEIGHBORS`, and
    `NONE` on a dump built with a different grouping.
  - `rejectsAnUnknownGrouping`: `NONE`, `same_stack`, and the empty string throw
    `IllegalArgumentException` with the message
    `group must be same-stack, neighbors, or none`.
  - Extend the existing null case: `forRequest(dump, null, null, null)` is
    `dump` itself.
  - Extend `answers400WithTheMessageForABadParameter` or add a case so
    `?group=all` answers 400 with that message.

  Update the existing `forRequest` calls to pass `null` for `group`.

- [ ] **Step 2: Run `TestThreadDumpServlet` and confirm the new tests fail.**

- [ ] **Step 3: Implement.** `doGet` passes `req.getParameter("group")`.
  `forRequest` returns `dump` when all three parameters are null, and otherwise
  maps `group` with an exact, case-sensitive match. Update the class Javadoc
  and the `@param` list.

- [ ] **Step 4: Run `TestThreadDumpServlet` and confirm it passes.**

- [ ] **Step 5: Update the README** request parameter list with
  `/td?group=same-stack`, `neighbors`, or `none`, what each does, and the 400
  body.

- [ ] **Step 6: Run the generator and servlet suites.** All pass.

- [ ] **Step 7: Commit** with `feat(servlet): choose the thread grouping per request`.
