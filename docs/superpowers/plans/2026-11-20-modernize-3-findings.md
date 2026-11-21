# Findings, Sampling Window, and Frame Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open the dump with findings that name what looks abnormal, sample the
JVM over a short window to report current CPU, allocation, and GC activity, and
hide stack frames that carry no information.

**Architecture:** `generate()` reads the JVM once into an immutable
`JvmSnapshot`, after an optional sampling window whose start and end figures
give per-thread CPU and allocation and per-collector GC activity. Nine small
`Check` classes turn the snapshot into `Finding`s. A `FindingsSection` prints
them above the existing sections, which read the same snapshot and append
`<<<` markers from the findings. `StackFilter` decides which frames print.

**Tech Stack:** Gradle 9.9.2 (Kotlin DSL), Java 17 toolchain with all code
compiled at `options.release = 8`, tests run by `test` (Java 17) and
`testOnJava8`, JUnit Jupiter 5.14.4, AssertJ 3.27.7, Mockito 4.11.0,
`javax.servlet-api` 3.0.1 (compile only).

**Spec:** `docs/superpowers/specs/2026-11-20-findings-design.md`
**Issue:** <https://github.com/Widen/jvm-threaddump/issues/6>

## Global Constraints

- All code, tests included, compiles with `options.release = 8`: no Java 9+ API
  (no `List.of`, `Map.of`, `var`, `String.repeat`, `Optional.isEmpty`). Every
  test passes on both `test` (Java 17) and `testOnJava8`.
- The generator keeps zero runtime dependencies.
- `JvmThreadDump` stays immutable and safe to share between threads.
- Existing sections keep their order, titles, and the 22-column title padding
  (`Line.PADDING`). Lines change only where a task says so.
- Everything a check reads comes from `JvmSnapshot`, and everything the
  snapshot reads comes through `JvmSources`. No test sleeps or reads a clock.
- Format every number with `Locale.US`, as the existing sections do.
- Thresholds are `static final` constants on the check that uses them, with the
  spec's values: 5, 10, 90%, 25% and 500 ms, 10%, 0.90, 50%, and list lengths 3
  and 5.
- All new types are package-private in `com.widen.util.td`. The public API
  grows by exactly `Builder.sampleWindow(Duration)`,
  `Builder.allFrames(boolean)`, and `JvmThreadDump.toBuilder()`.
- Each behavior gets one test; variations of one behavior share a
  `@ParameterizedTest`.
- Code style matches the existing sources: four-space indent, opening brace of
  a type on its own line. No comment restates code.
- Commit bodies wrap at 72 columns. Edit files with the Edit and Write tools.
- The generator's test command, used by every task unless it names another:

  ```sh
  mise exec -- ./gradlew :jvm-threaddump-generator:test :jvm-threaddump-generator:testOnJava8 -q --console=plain 2>&1 | tail -40; echo "exit=${PIPESTATUS[0]}"
  ```

  Expected: `exit=0`. If the harness refuses the pipeline, redirect the output
  to a scratchpad file and read its end.

## Review Focus

1. The calling thread is interrupted before any time elapses in the window.
   The dump prints `not sampled` and no rate divides by zero (Task 2).
2. The JVM reports deadlocked threads but the owner graph holds no cycle. The
   Deadlock finding still prints, listing the JVM's threads (Task 5).
3. A thread name or lock name holds `%`. It prints as written and no
   `String.format` call throws (Task 4).
4. A thread's CPU time reads -1 at the window's end, or the thread is gone.
   It is left out of Hot Threads; no negative percentage prints (Task 2).
5. A lock's owner is missing from the thread snapshot. The chain ends at that
   id and the finding names it by id (Task 5).

## Rulings

Calls this plan makes where the spec leaves the choice to the builder.

- R1. Exact wording of each finding's lines, pinned in the tasks' tests.
- R2. Percentages print as whole numbers rounded down; thresholds compare the
  unrounded ratio. Allocation rates print as whole MB per second, rounded.
- R3. A thread that allocated under 1 MB per second is left out of the
  allocator lines.
- R4. Marks are string keys: `thread:ID` (decimal), `pool:NAME`, `gc:NAME`,
  and `line:TITLE`.
- R5. A finding's stack frames print with `ThreadSection.formatFrame`, so they
  carry its four-space indent.
- R6. Tests against the real JVM build their dump with
  `sampleWindow(Duration.ZERO)`, except one smoke test with a 50 ms window.
- R7. `JvmThreadDump` gets package-private `sampleWindow()` and `allFrames()`
  accessors so the servlet's tests, which share the package, can check what a
  request built.
- R8. The pool and collector mock helpers move from `TestMemorySection` and
  `TestGcSection` into `FakeJvm`.
- R9. Stack Pile-up says threads "are in the same call", not "stuck", because
  a group can be doing real work.

---

### Task 1: Read the JVM once into `JvmSnapshot`

Preparatory refactor. The dump's text does not change.

**Files:**

- Create: `JvmSnapshot.java`, `ThreadSnapshot.java`, `PoolSnapshot.java`,
  `GcSnapshot.java` in `generator/src/main/java/com/widen/util/td/`
- Modify: `JvmThreadDump.java`, `JvmInfoSection.java`, `MemorySection.java`,
  `GcSection.java`, `DeadlockSection.java`, `ThreadSection.java`
- Test: create `TestJvmSnapshot.java`; modify `FakeJvm.java`,
  `FakeThreads.java`, and every `Test*Section.java` and `TestJvmThreadDump.java`
  for the new constructors

**Interfaces:**

- Produces:

```java
final class ThreadSnapshot {
    final Thread thread; final ThreadInfo info; final String name; final String toString;
    final long cpuNanos;             // total CPU time, -1 when unavailable
    final long windowCpuNanos;       // -1 until Task 2
    final long windowAllocatedBytes; // -1 until Task 2
    long id(); Thread.State state(); StackTraceElement[] stack();
}
final class PoolSnapshot {
    final String name; final MemoryType type; final MemoryUsage usage;
    final MemoryUsage collectionUsage; // null when the pool reports none
}
final class GcSnapshot {
    final String name; final long count; final long timeMillis;
    final long windowCount; final long windowTimeMillis; // 0 until Task 2
}
final class JvmSnapshot {
    static JvmSnapshot read(JvmSources jvm, Duration window); // window ignored until Task 2
    final List<ThreadSnapshot> threads;    // dump order: name ignoring case, name, id
    final long[] deadlockedIds;            // empty when the JVM reports none
    final List<PoolSnapshot> pools;        // pools with a non-null usage, JVM order
    final List<GcSnapshot> collectors;
    final double systemCpuLoad; final double processCpuLoad; // -1 when unavailable
    final long uptimeMillis;
    final long windowNanos;                // 0 = not sampled
    final boolean cpuTimeAvailable; final boolean allocationAvailable;
    final long generatingThreadId;         // Thread.currentThread().getId()
    boolean sampled();                     // windowNanos > 0
}
```

- Produces: section constructors `JvmInfoSection(JvmSources, JvmSnapshot,
  List<String>, Map<String, ?>)`, `MemorySection(JvmSources, JvmSnapshot)`,
  `GcSection(JvmSnapshot)`, `DeadlockSection(JvmSnapshot)`,
  `ThreadSection(JvmSnapshot)`.
- Produces test helpers on `FakeJvm`:
  `ThreadInfo addThread(String name, long id, Thread.State state, StackTraceElement... frames)`
  (adds the live thread and appends the info to what `dumpAllThreads` returns),
  `MemoryPoolMXBean addPool(String name, MemoryType type, MemoryUsage usage, MemoryUsage collectionUsage)`,
  `GarbageCollectorMXBean addCollector(String name, long count, long timeMillis)`,
  and `JvmSnapshot snapshot()` (`JvmSnapshot.read(this, Duration.ZERO)`). On
  `FakeThreads`:
  `static StackTraceElement frame(String className, String method)`, which uses
  the class's simple name plus `.java` as the file and line 1. `FakeJvm.threads`
  becomes a mock of `com.sun.management.ThreadMXBean`.

**Steps:**

- [ ] **Step 1: Write the failing tests**

```java
// TestJvmSnapshot
@Test void readsEachSourceOnce()
// FakeJvm with two threads, one pool, one collector; read(jvm, Duration.ZERO):
// verify(jvm.threads, times(1)).dumpAllThreads(false, false);
// verify(jvm.threads, times(1)).findDeadlockedThreads();
// verify(pool, times(1)).getUsage(); verify(collector, times(1)).getCollectionTime();
// verify the OS bean: times(1) for getSystemCpuLoad() and getProcessCpuLoad().

@Test void holdsThreadsInDumpOrderWithTheirCpuTime()
// threads "b" id 5 and "A" id 6, CPU time enabled, getThreadCpuTime(6) = 1_500_000_000:
// names [A, b]; threads.get(0).cpuNanos == 1_500_000_000; windowCpuNanos == -1.
// With CPU time disabled: cpuNanos == -1 and cpuTimeAvailable is false.

@Test void leavesOutAPoolWithoutUsageAndKeepsCollectionUsage()
// pools [("G1 Old Gen", HEAP, usage, collectionUsage), ("G1 Survivor Space", HEAP, null, null)]
// -> one PoolSnapshot, with both usages and the type.

@Test void reportsUnavailableCpuLoadAsMinusOne()
// jvm.operatingSystem = mock(java.lang.management.OperatingSystemMXBean.class)
// -> systemCpuLoad == -1 and processCpuLoad == -1.
```

Move the thread-ordering, both-snapshots, and rename tests' setup onto
`jvm.addThread` where that shortens them; their assertions stay.

- [ ] **Step 2: Run them to verify they fail**

Expected: compilation fails on `JvmSnapshot`.

- [ ] **Step 3: Implement**

`JvmSnapshot.read` performs every read the five sections make today for
threads, deadlocks, pools, collectors, CPU load, and uptime. The sections keep
reading `JvmSources` only for values no check uses: time, system properties,
environment, host details, heap totals, and `/proc/uptime`. `ThreadSection`
loses its private `ThreadSnapshot` and its sort; the join of live threads to
`ThreadInfo` and the sort move into `read`. `JvmThreadDump.generate` reads one
snapshot and passes it to each section.

- [ ] **Step 4: Run the module tests**

Expected: `exit=0`, with no change to any expected dump text.

- [ ] **Step 5: Commit**

`git commit -m "refactor: read the jvm once into a snapshot"`

### Task 2: Sampling window

**Files:**

- Modify: `JvmSources.java`, `PlatformJvmSources.java`, `JvmSnapshot.java`,
  `JvmThreadDump.java`
- Test: `TestJvmSnapshot.java`, `TestJvmThreadDump.java`, `FakeJvm.java`,
  `TestDump.java`, `TestDeadlock.java`

**Interfaces:**

- Consumes: `JvmSnapshot.read(JvmSources, Duration)` and the snapshot types
  (Task 1).
- Produces on `JvmSources`: `void pause(Duration duration) throws
  InterruptedException` and `long nanoTime()` (monotonic).
- Produces on `JvmThreadDump.Builder`: `public Builder sampleWindow(Duration
  window)`, default `Duration.ofSeconds(1)`, `NullPointerException` for null,
  `IllegalArgumentException` for negative. On `JvmThreadDump`:
  `public Builder toBuilder()` carrying the JVM sources, main arguments, custom
  values, and window; package-private `Duration sampleWindow()`.
- Produces on `FakeJvm`: `long nanoTime` (returned by `nanoTime()`),
  `int pauses`, and `Duration interruptPauseAfter` (null by default). `pause`
  adds the duration to `nanoTime` and counts the call; when
  `interruptPauseAfter` is set it adds that amount instead and throws
  `InterruptedException`.
- The window fields of `ThreadSnapshot`, `GcSnapshot`, and `JvmSnapshot` now
  hold real values.

**Steps:**

- [ ] **Step 1: Write the failing tests**

```java
// TestJvmSnapshot. CPU time enabled; allocation supported and enabled.
@Test void reportsWhatChangedDuringTheWindow()
// getAllThreadIds() = {23, 24}. Thread 23: getThreadCpuTime 1_000_000_000 then
// 1_600_000_000; getThreadAllocatedBytes 10 MB then 110 MB. Thread 24 is gone
// at the end (not in dumpAllThreads). Thread 25 is only in the end dump, with
// CPU 200_000_000 and 5 MB allocated. Collector count 5 then 7, time 100 then
// 400. System CPU load 0.10 then 0.95; process 0.05 then 0.40.
// read(jvm, Duration.ofSeconds(1)):
//   windowNanos == 1_000_000_000; sampled(); jvm.pauses == 1;
//   thread 23: windowCpuNanos 600_000_000, windowAllocatedBytes 100 MB;
//   thread 25: windowCpuNanos 200_000_000, windowAllocatedBytes 5 MB;
//   no ThreadSnapshot for 24;
//   collector: windowCount 2, windowTimeMillis 300, count 7, timeMillis 400;
//   systemCpuLoad 0.95, processCpuLoad 0.40.

@Test void doesNotPauseForAZeroWindow()
// read(jvm, Duration.ZERO): jvm.pauses == 0; !sampled(); windowCpuNanos == -1;
// getThreadCpuTime called once per thread, getSystemCpuLoad once.

@Test void finishesWithTheTimeThatElapsedWhenInterrupted()
// interruptPauseAfter = 250 ms; read(jvm, 1 s): windowNanos == 250_000_000 and
// Thread.interrupted() is true (which also clears the flag for later tests).

@Test void isNotSampledWhenInterruptedAtOnce()            // Review Focus 1
// interruptPauseAfter = Duration.ZERO: !sampled(); every window field is -1 or 0;
// Thread.interrupted() is true.

@Test void leavesOutWindowCpuForAThreadWhoseTimeIsUnavailable()   // Review Focus 4
// getThreadCpuTime(23) = 1_000_000_000 then -1: windowCpuNanos == -1.

@Test void leavesOutAllocationWhenUnsupported()
// isThreadAllocatedMemorySupported() false: allocationAvailable false,
// windowAllocatedBytes == -1, getThreadAllocatedBytes never called.

// TestJvmThreadDump
@ParameterizedTest void rejectsANullOrNegativeWindow()   // null -> NPE, -1 ms -> IAE
@Test void samplesForOneSecondByDefault()
// builder().jvmSources(fake).build().generate(): fake.nanoTime == 1_000_000_000.
@Test void carriesItsSettingsIntoANewBuilder()
// dump with main arguments, custom values, and a 5 s window:
// dump.toBuilder().build() generates the same text against the same FakeJvm
// state, and its sampleWindow() is 5 s.
```

- [ ] **Step 2: Run them to verify they fail**

Expected: compilation fails on `pause`, `sampleWindow`, and `toBuilder`.

- [ ] **Step 3: Implement**

In `read`, when the window is above zero: note `nanoTime()`, read each id of
`getAllThreadIds()` for CPU time and allocated bytes, read each collector's
count and time, and call `getSystemCpuLoad()` and `getProcessCpuLoad()` once
to start their measuring span. Call `pause(window)`; on
`InterruptedException`, call `Thread.currentThread().interrupt()` and carry on.
Then make the Task 1 reads and subtract. A thread with no start figure counts
its whole total. Allocation comes from `com.sun.management.ThreadMXBean` when
`jvm.threads()` is that type and the feature is supported and enabled.
`PlatformJvmSources.pause` sleeps; its `nanoTime` is `System.nanoTime()`.

`TestDump` and `TestDeadlock` build their dumps with
`sampleWindow(Duration.ZERO)` (R6). Add one test to `TestDump`,
`samplesTheRunningJvm`, that generates with a 50 ms window and asserts only
that a dump comes back with `Thread Count: `.

- [ ] **Step 4: Run the module tests**

- [ ] **Step 5: Commit**

`git commit -m "feat: sample cpu, allocation, and gc over a window"`

### Task 3: Frame filter and thread state counts

**Files:**

- Create: `StackFilter.java`
- Modify: `ThreadSection.java`, `JvmThreadDump.java`
- Test: create `TestStackFilter.java`; modify `TestThreadSection.java`,
  `TestJvmThreadDump.java`

**Interfaces:**

- Consumes: `ThreadSnapshot`, `JvmSnapshot`, `FakeJvm.addThread`,
  `FakeThreads.frame` (Task 1).
- Produces:

```java
final class StackFilter {
    static boolean isJdk(String className);         // java., javax., jdk., sun., com.sun.
    static boolean hidden(StackTraceElement frame);
    static List<StackTraceElement> kept(StackTraceElement[] stack); // frame 0 always, then frames not hidden
    static StackTraceElement firstKeptOutsideJdk(StackTraceElement[] stack); // null when none
}
```

- Produces: `ThreadSection(JvmSnapshot, boolean allFrames)`;
  `static String ThreadSection.formatStateCounts(Collection<ThreadSnapshot>)`
  returning `12 RUNNABLE, 43 BLOCKED` in `Thread.State` order without zero
  counts; `Builder.allFrames(boolean)` (default false), carried by
  `toBuilder()`; package-private `boolean JvmThreadDump.allFrames()`.

**Steps:**

- [ ] **Step 1: Write the failing tests**

```java
// TestStackFilter
@ParameterizedTest @CsvSource void hidesPlumbingFrames(String className, boolean hidden)
// hidden: sun.reflect.NativeMethodAccessorImpl, jdk.internal.reflect.DirectMethodHandleAccessor,
//   java.lang.reflect.Method, java.lang.invoke.LambdaForm$MH,
//   $AssetService_1a2b3c4d, $AssetService_1a2b3c4d$Invocation_find_5e6f,
//   com.sun.proxy.$Proxy12, jdk.proxy2.$Proxy12,
//   com.example.Dao$$EnhancerByCGLIB$$1f, com.example.Jobs$$Lambda$14/0x0000000800c03000,
//   org.apache.tapestry5.ioc.internal.RegistryImpl,
//   org.apache.tapestry5.commons.internal.services.PlasticProxyFactoryImpl,
//   org.apache.tapestry5.http.TapestryFilter,
//   org.eclipse.jetty.ee8.servlet.ServletHandler, org.glassfish.jersey.server.ServerRuntime,
//   org.glassfish.hk2.utilities.Binder, org.jvnet.hk2.internal.ServiceLocatorImpl,
//   javax.servlet.http.HttpServlet
// kept: com.example.Dao, com.example.Dao$Inner, com.example.$Odd, java.lang.Thread,
//   org.postgresql.core.PGStream, com.zaxxer.hikari.pool.HikariPool, org.hibernate.loader.Loader

@Test void keepsTheTopFrameEvenWhenHidden()
// [org.eclipse.jetty.io.ManagedSelector.select, java.lang.reflect.Method.invoke, com.example.A.run]
// -> kept = [ManagedSelector.select, A.run]

@Test void findsTheFirstKeptFrameOutsideTheJdk()
// [sun.nio.ch.NioSocketImpl.read, $Dao_1a.find, org.postgresql.core.PGStream.receive, com.example.Dao.find]
// -> PGStream.receive; a stack of JDK frames only -> null; an empty stack -> null.

// TestThreadSection
@Test void hidesPlumbingFramesAndCountsThem()
// stack [com.example.Dao.find, $Dao_1a.find, java.lang.reflect.Method.invoke,
//        com.example.Api.get, org.eclipse.jetty.server.Server.handle, java.lang.Thread.run]:
// header, "    com.example.Dao.find(Dao.java:1)", "    com.example.Api.get(Api.java:1)",
// "    java.lang.Thread.run(Thread.java:1)", "    ... 3 frames hidden", "".
@Test void countsOneHiddenFrameInTheSingular()     // "    ... 1 frame hidden"
@Test void printsEveryFrameWhenAskedForAllFrames()
// new ThreadSection(snapshot, true): all six frames, no count line.
@Test void countsThreadsByState()
// 2 RUNNABLE, 1 BLOCKED, 3 WAITING -> titled("Thread Count", "6 (2 RUNNABLE, 1 BLOCKED, 3 WAITING)");
// no threads -> titled("Thread Count", "0").
```

Existing `Thread Count` assertions gain the state counts, such as
`titled("Thread Count", "1 (1 WAITING)")`.

- [ ] **Step 2: Run them to verify they fail**

- [ ] **Step 3: Implement**

The hidden rules, each a class-name test:

| Kind | Rule |
| --- | --- |
| Reflection | starts with `sun.reflect.`, `jdk.internal.reflect.`, `java.lang.reflect.`, or `java.lang.invoke.` |
| Proxies | has no `.` and starts with `$`; starts with `com.sun.proxy.` or `jdk.proxy`; contains `$$` |
| Tapestry | starts with `org.apache.tapestry5.` |
| Server | starts with `org.eclipse.jetty.`, `org.glassfish.jersey.`, `org.glassfish.hk2.`, `org.jvnet.hk2.`, or `javax.servlet.` |

`JvmThreadDump.generate` passes its `allFrames` setting to `ThreadSection`.

- [ ] **Step 4: Run the module tests**

- [ ] **Step 5: Commit**

`git commit -m "feat: hide plumbing frames and count threads by state"`

### Task 4: Findings block and markers

**Files:**

- Create: `Finding.java`, `Check.java`, `Checks.java`, `Findings.java`,
  `FindingsSection.java`
- Modify: `JvmThreadDump.java`, `JvmInfoSection.java`, `MemorySection.java`,
  `GcSection.java`, `ThreadSection.java`
- Test: create `TestFindingsSection.java`, `TestFindings.java`; modify the
  section tests for the new constructor argument, and `TestDump.java`

**Interfaces:**

- Consumes: `JvmSnapshot` (Tasks 1 and 2).
- Produces:

```java
final class Finding {
    Finding(String title, List<String> lines, Collection<String> marks); // lines has at least one entry
    final String title; final List<String> lines; final Set<String> marks;
    static String threadMark(long id);     // "thread:23"
    static String poolMark(String name);   // "pool:G1 Old Gen"
    static String gcMark(String name);     // "gc:G1 Young Generation"
    static String lineMark(String title);  // "line:CPU Load"
}
interface Check {
    boolean applies(JvmSnapshot snapshot);          // false: skipped and not counted
    List<Finding> findings(JvmSnapshot snapshot);   // empty when nothing is flagged
}
final class Checks { static final List<Check> ALL; } // empty in this task; later tasks append in spec order
final class Findings {
    static final Findings NONE;                      // no checks, no findings
    static Findings run(JvmSnapshot snapshot, List<Check> checks);
    final int checksRun; final int checksFlagged; final List<Finding> findings;
    String marker(String mark);                      // "" or " <<< Title" or " <<< Title, Other"
}
final class FindingsSection { FindingsSection(Findings, JvmSnapshot); List<Line> lines(); }
```

- Produces: section constructors `JvmInfoSection(JvmSources, JvmSnapshot,
  Findings, List<String>, Map<String, ?>)`, `MemorySection(JvmSources,
  JvmSnapshot, Findings)`, `GcSection(JvmSnapshot, Findings)`,
  `ThreadSection(JvmSnapshot, Findings, boolean allFrames)`.

**Steps:**

- [ ] **Step 1: Write the failing tests**

Tests pass stub `Check`s written as lambdas or small classes.

```java
// TestFindingsSection
@ParameterizedTest void printsNoneWithTheCheckCountAndWindow()
// 2 applying checks, no findings, zero window -> [titled("Findings", "None (2 checks, not sampled)"), ""]
// same with a 1 s window -> "None (2 checks, sampled over 1.0s)"
// 1 applying check -> "None (1 check, not sampled)"

@Test void printsFindingsInCheckOrderUnderACount()
// checks: A (two findings), B (applies() false), C (none), D (one finding); 1 s window:
// titled("Findings", "2 of 3 checks flagged something (sampled over 1.0s)"), "",
// titled("Alpha", "first"), continued("second"), "",
// titled("Alpha", "another"), "",
// titled("Delta", "only"), ""
// A single applying check that flags -> "1 of 1 check flagged something (...)".

@Test void printsPercentSignsAsWritten()                    // Review Focus 3
// Finding("Alpha", ["\"100%s\" waits for lock%d"], []) renders that text unchanged.

// TestFindings
@Test void marksALineWithEachDistinctTitleInOrder()
// findings Alpha{pool:X}, Alpha{pool:X}, Beta{pool:X, thread:23}:
// marker("pool:X") == " <<< Alpha, Beta"; marker("thread:23") == " <<< Beta"; marker("pool:Y") == "".

// One marker test per section, each with Findings built from a stub check:
// TestThreadSection.marksAThreadHeader  -> header ends with " <<< Alpha"
// TestMemorySection.marksAPoolLine      -> titled("G1 Old Gen", formatUsage(usage) + " <<< Alpha")
// TestGcSection.marksACollectorLine     -> "... doing 12 collections <<< Alpha"
// TestJvmInfoSection.marksTheCpuLoadLine -> "... (0.0 to 1.0) <<< Alpha"

// TestJvmThreadDump.rendersSectionsInOrder: FindingsSection first, then the five sections.
// TestDump: also contains "Findings: ".
```

- [ ] **Step 2: Run them to verify they fail**

- [ ] **Step 3: Implement**

`FindingsSection` prints the header as a `TitledLine`, a `BlankLine`, then per
finding a `MultiLine(title, lines)` and a `BlankLine`. The window text is
`String.format(Locale.US, "sampled over %.1fs", windowNanos / 1e9)` or
`not sampled`. Pass a finding's text to `String.format` only as an argument,
never as the format. Each section appends `findings.marker(...)` to the line
it prints: the thread header, the pool's value, the collector's value, and the
`CPU Load` value. Existing section tests pass `Findings.NONE`.
`JvmThreadDump.generate` runs `Findings.run(snapshot, Checks.ALL)`.

- [ ] **Step 4: Run the module tests**

- [ ] **Step 5: Commit**

`git commit -m "feat: print a findings block and mark flagged lines"`

### Task 5: Deadlock and Lock Pile-up checks

**Files:**

- Create: `LockGraph.java`, `DeadlockCheck.java`, `LockPileUpCheck.java`
- Modify: `JvmSnapshot.java`, `DeadlockSection.java`, `Checks.java`
- Test: create `TestLockGraph.java`, `TestDeadlockCheck.java`,
  `TestLockPileUpCheck.java`; modify `TestDeadlockSection.java`,
  `TestDeadlock.java`

**Interfaces:**

- Consumes: `Check`, `Finding`, `Checks.ALL` (Task 4); `StackFilter.kept`
  (Task 3); `ThreadSection.formatFrame`.
- Produces:

```java
final class LockGraph {
    static LockGraph of(List<ThreadSnapshot> threads);
    List<List<ThreadSnapshot>> cycles();   // each starts at its first thread in dump order and follows owners
    Set<Long> inOrBehindCycles();          // ids of cycle threads and of threads waiting behind them
    List<ThreadSnapshot> behind(List<ThreadSnapshot> cycle); // dump order, cycle threads excluded
    Map<Long, List<ThreadSnapshot>> waitersByHolder(); // holder id -> every thread behind it; no cycle threads
}
```

- Produces: `LockGraph JvmSnapshot.locks()`, built once per snapshot;
  `static final int LockPileUpCheck.MIN_WAITERS = 5`. `Checks.ALL` becomes
  `[DeadlockCheck, LockPileUpCheck]`.

A thread **waits on an owner** when `getLockOwnerId()` is not -1 and the thread
is `BLOCKED`, or is `WAITING` or `TIMED_WAITING` with a top frame whose class
is not `java.lang.Object`. A **holder** is the id at the end of a chain: a
thread that waits on no owner, or an id missing from the snapshot.

**Steps:**

- [ ] **Step 1: Write the failing tests**

```java
// TestLockGraph
@Test void findsACycleAndTheThreadsBehindIt()
// a(1) BLOCKED on 2, b(2) BLOCKED on 1, c(3) BLOCKED on 1, d(4) BLOCKED on 3, e(5) RUNNABLE:
// cycles() == [[a, b]]; behind(cycle) == [c, d]; inOrBehindCycles() == {1, 2, 3, 4};
// waitersByHolder() is empty.
@Test void followsAChainToItsHolder()
// h(10) RUNNABLE; w1(11) BLOCKED on 10; m(12) WAITING on 10, top frame sun.misc.Unsafe.park;
// w2(13) BLOCKED on 12: waitersByHolder() == {10: [m, w1, w2]} (dump order).
@Test void ignoresAThreadInsideObjectWait()
// p(20) WAITING, owner 10, top frame java.lang.Object.wait: not a waiter.
@Test void endsAChainAtAnOwnerMissingFromTheSnapshot()      // Review Focus 5
// w(30) BLOCKED on 99, no thread 99: waitersByHolder() == {99: [w]}.

// TestDeadlockCheck. Frames come from FakeThreads.frame.
@Test void printsTheCycleAndCountsTheThreadsBehindIt()
// a(1) BLOCKED on 2 for "java.lang.Object@1a", stack [com.example.A.run];
// b(2) BLOCKED on 1 for "java.lang.Object@2b", stack [com.example.B.run];
// c(3) BLOCKED on 1; findDeadlockedThreads() = {3, 1, 2} (Java 8 includes c).
// One finding, title "Deadlock", lines:
//   "2 threads block each other and will never proceed"
//   "\"a\" (0x1) waits for java.lang.Object@1a, held by \"b\""
//   "    com.example.A.run(A.java:1)"
//   "\"b\" (0x2) waits for java.lang.Object@2b, held by \"a\""
//   "    com.example.B.run(B.java:1)"
//   "1 other thread waits behind them"
// marks == {thread:1, thread:2}. With d(4) also behind: "2 other threads wait behind them".
// With nobody behind: no such line. A null lock name prints "waits for a lock, held by".
@Test void printsOneFindingPerCycle()                  // two separate two-thread cycles
@Test void findsNothingWithoutAJvmReport()
// the same cycle but findDeadlockedThreads() = null -> no findings.
@Test void listsTheJvmsThreadsWhenItFindsNoCycle()          // Review Focus 2
// findDeadlockedThreads() = {1, 2}, both RUNNABLE with no owner:
//   "2 threads are deadlocked, by the JVM's report", "\"a\" (0x1)", "\"b\" (0x2)"
// marks both threads. An id missing from the snapshot prints as "thread 0x63".

// TestLockPileUpCheck
@ParameterizedTest void flagsAHolderWithFiveOrMoreThreadsBehindIt()   // 4 -> none, 5 -> one
@Test void printsTheHolderAndItsFrames()
// h(10) RUNNABLE, stack [com.example.H.work, $H_1a.work, com.example.H.run, java.lang.Thread.run,
// com.example.Main.main], six threads behind it:
//   "6 threads wait on a lock held by \"h\" (0xa, RUNNABLE)"
//   "    com.example.H.work(H.java:1)", "    com.example.H.run(H.java:1)",
//   "    java.lang.Thread.run(Thread.java:1)"
// marks == {thread:10}.
@Test void namesAMissingHolderById()
//   "5 threads wait on a lock held by thread 0x63, which is not in the dump"; no marks.
@Test void filtersItsFramesEvenWhenTheDumpPrintsAllFrames()
// TestJvmThreadDump: the same fixture, dump built with allFrames(true): the thread list
// holds "$H_1a.work" and the Lock Pile-up finding's lines do not.
@Test void printsTheLargestPileUpFirst()               // holders with 5 and 7 waiters
@Test void leavesDeadlocksToTheDeadlockCheck()         // a cycle with 6 threads behind it -> none

// TestDeadlockSection
@Test void printsTheCycleThreadsByName()
// the first DeadlockCheck fixture -> titled("Deadlocked Threads", "\"a\" (0x1), \"b\" (0x2)")
@Test void printsTheJvmsThreadsWhenItFindsNoCycle()
// {1, 99}, no cycle, 99 missing -> titled("Deadlocked Threads", "\"a\" (0x1), 0x63")
// printsNoneWithoutDeadlocks stays.

// TestDeadlock (real JVM): the dump holds a "Deadlock: 2 threads block each other" line and
// both fixture thread names on the "Deadlocked Threads" line; after close, "Deadlocked Threads: None".
```

- [ ] **Step 2: Run them to verify they fail**

- [ ] **Step 3: Implement**

`DeadlockCheck` always applies and returns nothing when `deadlockedIds` is
empty. It prints frame 0 of each cycle thread's stack and leaves the frame
line out for an empty stack. `LockPileUpCheck` prints the first 3 of
`StackFilter.kept` for the holder, and orders findings by waiter count, then by
the holder's dump position. `DeadlockSection` prints `None` when
`deadlockedIds` is empty, else the cycle threads, else the JVM's list.

- [ ] **Step 4: Run the module tests**

- [ ] **Step 5: Commit**

`git commit -m "feat: explain deadlocks and flag lock pile-ups"`

### Task 6: Stack Pile-up check

**Files:**

- Create: `StackPileUpCheck.java`
- Modify: `Checks.java`
- Test: create `TestStackPileUpCheck.java`

**Interfaces:**

- Consumes: `JvmSnapshot.locks()`, `LockPileUpCheck.MIN_WAITERS` (Task 5);
  `StackFilter.isJdk`, `StackFilter.firstKeptOutsideJdk`,
  `ThreadSection.formatStateCounts` (Task 3).
- Produces: `StackPileUpCheck` with `static final int MIN_THREADS = 10` and
  `static boolean idle(ThreadSnapshot thread)`; appended to `Checks.ALL`.

**Steps:**

- [ ] **Step 1: Write the failing tests**

```java
// The pile-up stack used below:
// [sun.nio.ch.NioSocketImpl.read, java.io.InputStream.read, $Dao_1a.find,
//  org.postgresql.core.PGStream.receive, com.example.Dao.find]
@ParameterizedTest void flagsTenOrMoreThreadsInTheSameCall()    // 9 -> none, 10 -> one
@Test void printsTheSharedFramesAndFirstThreeNames()
// "db-0" .. "db-9", seven RUNNABLE and three TIMED_WAITING:
//   "10 threads are in the same call (7 RUNNABLE, 3 TIMED_WAITING)"
//   "    sun.nio.ch.NioSocketImpl.read(NioSocketImpl.java:1)"
//   "    org.postgresql.core.PGStream.receive(PGStream.java:1)"
//   "\"db-0\", \"db-1\", \"db-2\" and 7 more"
// marks is empty.
@Test void printsOneFrameWhenTheTopFrameIsTheKey()
// ten threads with stack [com.example.Job.spin, java.lang.Thread.run]: one frame line.
@Test void groupsByTheLibraryUnderTheSameTopFrame()
// five threads under PGStream.receive and five under org.apache.http.impl.io.SessionInputBufferImpl.read,
// all with the same top frame -> no finding.
@Test void ignoresLineNumbersWhenGrouping()            // same class and method, different lines -> one group
@ParameterizedTest void leavesOutThreadsThatAreNotPiledUp()
// each case has ten matching threads and expects no finding:
//   idle Jetty workers: WAITING, [sun.misc.Unsafe.park,
//     org.eclipse.jetty.util.thread.QueuedThreadPool.idleJobPoll, java.lang.Thread.run];
//   threads with an empty stack;
//   threads behind a lock pile-up of ten;
//   threads in and behind a deadlock;
//   nine matching threads plus the generating thread.
@ParameterizedTest void treatsAWaitingThreadWithOnlyLibraryFramesAsIdle()
// idle: WAITING or TIMED_WAITING with frames only from java., javax., jdk., sun., com.sun.,
//   org.eclipse.jetty., org.apache.tomcat., org.apache.catalina., io.netty., com.zaxxer.hikari.
// not idle: the same stack when RUNNABLE or BLOCKED; a WAITING stack with one com.example frame.
@Test void printsTheLargestGroupFirst()
```

- [ ] **Step 2: Run them to verify they fail**

- [ ] **Step 3: Implement**

The group key is the class and method of frame 0 and of `firstKeptOutsideJdk`,
which may be null or the same frame. The printed frames and names come from the
group's first thread in dump order. A thread is left out when it is idle, is the
generating thread, is in `locks().inOrBehindCycles()`, or waits behind a holder
with `MIN_WAITERS` or more.

- [ ] **Step 4: Run the module tests**

- [ ] **Step 5: Commit**

`git commit -m "feat: flag threads piled up in the same call"`

### Task 7: Heap Full, Metadata Full, and GC Overhead checks

**Files:**

- Create: `HeapFullCheck.java`, `MetadataFullCheck.java`,
  `GcOverheadCheck.java`, `Allocators.java`
- Modify: `Checks.java`
- Test: create `TestHeapFullCheck.java`, `TestMetadataFullCheck.java`,
  `TestGcOverheadCheck.java`, `TestAllocators.java`

**Interfaces:**

- Consumes: `PoolSnapshot`, `GcSnapshot`, the window fields (Tasks 1 and 2);
  `Finding.poolMark`, `Finding.gcMark` (Task 4).
- Produces: `static List<String> Allocators.lines(JvmSnapshot snapshot)`, empty
  when the snapshot is not sampled, allocation is unavailable, or no thread
  reached 1 MB per second; the three checks, appended to `Checks.ALL` in the
  order Heap Full, Metadata Full, GC Overhead.

**Steps:**

- [ ] **Step 1: Write the failing tests**

```java
// TestAllocators. 1 s window.
@Test void listsTheTopThreeAllocatorsByRate()
// import-7 (0x33) 412 MB, qtp-4 (0x49) 96 MB, c 50 MB, d 2 MB, e 0.5 MB:
//   "Top allocators: \"import-7\" (0x33) 412 MB/s"
//   "                \"qtp-4\" (0x49) 96 MB/s"
//   "                \"c\" (0x3) 50 MB/s"
// A 2 s window halves each rate. Not sampled, or allocation unavailable -> empty.

// TestHeapFullCheck. Pool max 4,000 MB.
@ParameterizedTest void flagsAnOldPoolOnlyWhenBothReadingsAreHigh()
// (name, type, used now, used after collection) -> flagged:
//   G1 Old Gen, HEAP, 3,600, 3,600 -> yes      PS Old Gen, HEAP, 3,880, 3,840 -> yes
//   Tenured Gen, HEAP, 3,880, 3,840 -> yes     G1 Old Gen, HEAP, 3,599, 3,800 -> no
//   G1 Old Gen, HEAP, 3,800, 3,599 -> no       G1 Old Gen, HEAP, 3,800, null -> no
//   G1 Old Gen, HEAP, 3,800, 0 -> no           G1 Eden Space, HEAP, 3,880, 3,840 -> no
//   Old Things, NON_HEAP, 3,880, 3,840 -> no   G1 Old Gen with max -1 -> no
@Test void printsBothPercentagesAndTheAllocators()
// G1 Old Gen 3,880 now and 3,840 after, 1 s window, import-7 allocating 412 MB:
//   "G1 Old Gen holds 97% of its max, 96% after its last collection"
//   "Top allocators: \"import-7\" (0x33) 412 MB/s"
// marks == {pool:G1 Old Gen}. Not sampled: the first line only.

// TestMetadataFullCheck. Pool max 1,000 MB.
@ParameterizedTest void flagsAMetadataPoolAtNinetyPercent()
// Metaspace 900 -> "Metaspace holds 90% of its max", marks {pool:Metaspace}; 899 -> none;
// Compressed Class Space, Code Cache, CodeHeap 'profiled nmethods' at 950 -> flagged;
// Metaspace with max -1 -> none; G1 Old Gen at 950 -> none.

// TestGcOverheadCheck. FakeJvm uptime is 90,061,000 ms.
@ParameterizedTest void flagsGcTimeInTheWindow()
// (window, GC ms in window, collections) -> first line or none:
//   1 s, 500, 2 -> "GC took 50% of the last 1.0s in 2 collections"
//   1 s, 300, 1 -> none        4 s, 1,000, 3 -> "GC took 25% of the last 4.0s in 3 collections"
//   4 s, 999, 3 -> none        2 s, 500, 1 -> "GC took 25% of the last 2.0s in 1 collection"
@ParameterizedTest void flagsGcTimeOverTheJvmsLifetime()
// total GC ms: 9,006,100 -> "GC has taken 10% of JVM uptime, an average since the JVM started";
// 9,006,099 -> none. Flags with and without a window.
@Test void printsBothMeasuresAndTheAllocators()
// both flag: the window line, the allocator lines, then the lifetime line.
@Test void marksTheCollectorsItCounted()
// window measure: marks only collectors with windowCount > 0;
// lifetime measure alone: marks every summed collector.
@Test void leavesOutConcurrentCollectorsAndUnknownTimes()
// "ZGC Cycles" and "G1 Concurrent GC" are neither summed nor marked; a time of -1 counts as 0.
```

- [ ] **Step 2: Run them to verify they fail**

- [ ] **Step 3: Implement**

An old-generation pool has type `HEAP` and a name containing `Old` or
`Tenured`. A metadata pool is named `Metaspace`, `Compressed Class Space`, or
`Code Cache`, or its name starts with `CodeHeap`. Both pool checks skip a pool
whose max is zero or negative. Each pool check returns one finding per flagged
pool, in the snapshot's pool order. `GcOverheadCheck` returns at most one
finding, and skips the lifetime measure when uptime is zero or negative. All
three checks always apply.

- [ ] **Step 4: Run the module tests**

- [ ] **Step 5: Commit**

`git commit -m "feat: flag a full heap, full metadata pools, and gc overhead"`

### Task 8: CPU Saturated, File Descriptors, and Hot Threads checks

**Files:**

- Create: `CpuSaturatedCheck.java`, `FileDescriptorsCheck.java`,
  `HotThreadsCheck.java`
- Modify: `JvmSnapshot.java`, `JvmInfoSection.java`, `Checks.java`
- Test: create `TestCpuSaturatedCheck.java`, `TestFileDescriptorsCheck.java`,
  `TestHotThreadsCheck.java`; modify `TestJvmSnapshot.java`,
  `TestJvmInfoSection.java`

**Interfaces:**

- Consumes: the window fields (Task 2); `Finding.lineMark`,
  `Finding.threadMark` (Task 4); `StackFilter.kept` (Task 3).
- Produces on `JvmSnapshot`: `final long openFileDescriptors` and
  `final long maxFileDescriptors`, both -1 unless `jvm.operatingSystem()` is a
  `com.sun.management.UnixOperatingSystemMXBean`. The three checks, appended to
  `Checks.ALL`, which is now complete in spec order.

**Steps:**

- [ ] **Step 1: Write the failing tests**

```java
// TestCpuSaturatedCheck
@ParameterizedTest void flagsSystemLoadAtNinetyPercent()
// 0.90 with JVM 0.40 -> "system CPU load is 0.90; this JVM uses 0.40 (0.0 to 1.0)",
//   marks {line:CPU Load}; 0.89 -> none.
@ParameterizedTest void doesNotApplyWithoutBothLoads()      // system -1, or process -1 -> applies() false

// TestFileDescriptorsCheck. Limit 65,536.
@ParameterizedTest void flagsOpenDescriptorsAtNinetyPercent()
// 58,983 -> "58,983 of 65,536 file descriptors are open (90%)", marks {line:File Descriptors};
// 58,982 -> none.
@Test void doesNotApplyWithoutTheUnixBean()

// TestJvmInfoSection
@Test void printsFileDescriptorsAfterSystemMemory()
// Unix bean with 412 open of 65,536 -> titled("File Descriptors", "412 open; 65,536 limit")
// directly after the System Memory line; the default FakeJvm prints "Not Available".
// A stub finding marking line:File Descriptors appends " <<< Alpha".

// TestHotThreadsCheck. 1 s window, CPU time enabled.
@Test void listsThreadsThatUsedHalfACore()
// a(1) 900 ms with stack [com.example.A.spin, $A_1a.spin, com.example.A.run,
// java.lang.Thread.run, com.example.Main.main]; b(2) 500 ms, empty stack; c(3) 499 ms:
//   "2 threads used 50% or more of one core over the last 1.0s"
//   "\"a\" (0x1) 90% of one core"
//   "    com.example.A.spin(A.java:1)", "    com.example.A.run(A.java:1)",
//   "    java.lang.Thread.run(Thread.java:1)"
//   "\"b\" (0x2) 50% of one core"
// marks == {thread:1, thread:2}. One thread -> "1 thread used 50% or more ...".
@Test void listsAtMostFiveThreadsAndCountsThemAll()
// seven threads at 60% to 66%: "7 threads used ..." then the five highest, highest first.
@Test void leavesOutAThreadWithoutAWindowReading()      // windowCpuNanos == -1 is not listed
@ParameterizedTest void doesNotApplyWithoutAWindowOrCpuTime()
// zero window -> applies() false; CPU time disabled -> applies() false.
```

- [ ] **Step 2: Run them to verify they fail**

- [ ] **Step 3: Implement**

`JvmInfoSection` adds the `File Descriptors` line after `System Memory` and
appends the marker to it. `HotThreadsCheck` marks only the threads it lists.
After this task, `TestJvmThreadDump` gains `countsNineChecksWithAWindow`: a
`FakeJvm` with CPU time enabled and the Unix OS bean prints
`None (9 checks, sampled over 1.0s)`, and the same dump built with
`sampleWindow(Duration.ZERO)` prints `None (8 checks, not sampled)`.

- [ ] **Step 4: Run the module tests**

- [ ] **Step 5: Commit**

`git commit -m "feat: flag cpu saturation, file descriptors, and hot threads"`

### Task 9: Servlet `window` and `frames` parameters

**Files:**

- Modify: `servlet/src/main/java/com/widen/util/td/ThreadDumpServlet.java`
- Test: `servlet/src/test/java/com/widen/util/td/TestThreadDumpServlet.java`

**Interfaces:**

- Consumes: `JvmThreadDump.toBuilder()`, `Builder.sampleWindow(Duration)`,
  `Builder.allFrames(boolean)`, and the package-private `sampleWindow()` and
  `allFrames()` accessors (Tasks 2 and 3).
- Produces: package-private
  `static JvmThreadDump ThreadDumpServlet.forRequest(JvmThreadDump dump, String window, String frames)`,
  which returns `dump` itself when both parameters are null and throws
  `IllegalArgumentException` with the response text for a bad value.

**Steps:**

- [ ] **Step 1: Write the failing tests**

```java
@Test void buildsADumpWithTheRequestedWindowAndFrames()
// forRequest(dump, "5", null): sampleWindow() == 5 s, allFrames() false;
// forRequest(dump, null, "all"): allFrames() true, the dump's own window;
// forRequest(dump, "0", "all"): Duration.ZERO and allFrames() true;
// forRequest(dump, null, null) is the same instance.
@ParameterizedTest void rejectsABadWindow()
// "-1", "31", "1.5", "abc", "", "+5", "99999999999" ->
//   IllegalArgumentException("window must be a whole number of seconds from 0 to 30")
@ParameterizedTest void acceptsWindowsFromZeroToThirty()        // "0", "30", "07"
@Test void rejectsFramesOtherThanAll()
// "some" -> IllegalArgumentException("frames must be all")
@Test void servesTheRequestedDump()
// request with window=0 and frames=all: the body contains "not sampled".
@Test void answers400WithTheMessageForABadParameter()
// request with window=31: setStatus(400), content type "text/plain; charset=UTF-8",
// body "window must be a whole number of seconds from 0 to 30", and the same
// four cache headers as a dump.
```

The existing tests that generate a real dump pass a dump built with
`sampleWindow(Duration.ZERO)`, or a request with `window=0` for the no-arg
servlet, so they do not sleep.

- [ ] **Step 2: Run them to verify they fail**

Run:
`mise exec -- ./gradlew :jvm-threaddump-servlet:test :jvm-threaddump-servlet:testOnJava8 -q --console=plain 2>&1 | tail -40; echo "exit=${PIPESTATUS[0]}"`

- [ ] **Step 3: Implement**

`doGet` reads `window` and `frames` with `getParameter`, calls `forRequest`,
and answers 400 from the one `catch` of `IllegalArgumentException` around that
call. The window matches `\d{1,2}` and is at most 30. Both responses set the
same content type and cache headers.

- [ ] **Step 4: Run the servlet module tests**

Same command as Step 2. Expected: `exit=0`.

- [ ] **Step 5: Commit**

`git commit -m "feat(servlet): set the window and frames from the url"`

### Task 10: README and final checks

**Files:**

- Modify: `readme.md`

**Steps:**

- [ ] **Step 1: Update the README**

Add a Findings section after Customization with:

- what the block is, with the three-finding example from the spec, and that
  `None` means the checks flagged nothing, not that the JVM is healthy;
- a table of the nine checks with each threshold and whether it needs a
  window;
- the sampling window: the one-second default, `sampleWindow(Duration)`,
  `/td?window=5`, the 0 to 30 range, and that `window=0` returns at once;
- the CPU load caveats: the figure covers the time since the JVM's load was
  last read, and in a container with a CPU limit it is the share of that limit;
- the frame filter: the four kinds of hidden frame, the `... N frames hidden`
  line, `allFrames(true)`, and `/td?frames=all`;
- `toBuilder()`, with a short example that builds a one-off dump.

Update Customization for the new builder methods. Add `File Descriptors` to
the Glossary. Regenerate Example Output from a real run of `TestDump` on Java
17 and trim it as the current example is trimmed.

- [ ] **Step 2: Format the README**

Run:
`mise exec rumdl -- rumdl fmt --flavor gfm --config 'MD013.reflow = true' readme.md`
then `mise exec rumdl -- rumdl check --flavor gfm readme.md`. Expected: no
findings in the text this task wrote.

- [ ] **Step 3: Run every test on both JVMs**

Run:
`mise exec -- ./gradlew test testOnJava8 -q --console=plain 2>&1 | tail -40; echo "exit=${PIPESTATUS[0]}"`
Expected: `exit=0`.

- [ ] **Step 4: Check the class file version**

Run:
`mise exec -- javap -v -cp generator/build/classes/java/main com.widen.util.td.JvmThreadDump | rg 'major version'`
Expected: `major version: 52`.

- [ ] **Step 5: Commit**

`git commit -m "docs(readme): describe findings, the window, and the frame filter"`

After this task the controller has a `sonnet` subagent copyedit the new README
text with the `elements-of-style:writing-clearly-and-concisely` skill, then
runs the whole-branch code review.
