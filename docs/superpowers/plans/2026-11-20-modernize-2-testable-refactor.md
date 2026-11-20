# Testable Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the generator into small sections that read injected JVM data,
replace the subclass hooks with a builder, fix the code review's bugs, and pin
every section of the dump with unit tests.

**Architecture:** `JvmSources` is a package-private interface over everything
the dump reads from the JVM and host; `JvmSources.platform()` reads the real
JVM, and tests pass a `FakeJvm` built from Mockito mocks. Five package-private
section classes turn a `JvmSources` into `List<Line>`. `JvmThreadDump` becomes
a public final class, built by a builder, that renders the sections in order.
`ThreadDumpServlet` serves one `JvmThreadDump` as UTF-8 text.

**Tech Stack:** Gradle 9.9.2 (Kotlin DSL), Java 17 toolchain with all code
compiled at `options.release = 8`, tests run by `test` (Java 17) and
`testOnJava8`, JUnit Jupiter 5.14.4, AssertJ 3.27.7, Mockito 4.11.0,
`javax.servlet-api` 3.0.1 (compile only).

**Spec:**
`docs/superpowers/specs/2026-11-19-modernize-build-and-tests-design.md`
(layer 2)

**Issue:** <https://github.com/Widen/jvm-threaddump/issues/3>

**Prerequisite:** layer 1 (`modernize-1-gradle-9`) includes the commit that
moves the toolchain to Java 17 with `options.release = 8` and adds
`testOnJava8`, and this branch is rebased onto it. Check with
`rg -n 'testOnJava8' build.gradle.kts`.

## Global Constraints

- All code, tests included, compiles with `options.release = 8`: no Java 9+
  API anywhere (no `List.of`, `Map.of`, `var`, or `String.repeat`), and class
  files stay major version 52. Every test passes on both `test` (Java 17) and
  `testOnJava8`.
- Public API after this layer: `JvmThreadDump` (final class: `create()`,
  `builder()`, `generate()`), `JvmThreadDump.Builder` (`mainArguments`,
  `customValues`, `build`), and `ThreadDumpServlet` (no-arg constructor and a
  `ThreadDumpServlet(JvmThreadDump)` constructor). Everything else in
  `com.widen.util.td` is package-private, including `Line`.
- Section order, section titles, and the 22-column title padding
  (`Line.PADDING`) stay as they are, except where a bug fix below changes a
  line.
- The generator keeps zero runtime dependencies; the servlet module keeps the
  servlet API `compileOnly`.
- Each behavior gets one test; variations of one behavior share a
  `@ParameterizedTest`.
- Tests that compare formatted numbers and dates rely on the test JVM's
  `en_US` default locale, which Task 1 sets in the root build.
- Code style matches the existing sources: four-space indent, opening brace of
  a type on its own line.
- Commit bodies wrap at 72 columns. Edit files with the Edit and Write tools.

## Review Focus

1. A `/proc/uptime` that exists but does not parse prints "Not Available" for
   both uptime lines instead of throwing (Task 2).
2. A collector whose `getCollectionTime()` returns -1 prints "Not Available"
   instead of throwing (Task 3).
3. A memory pool with zero committed bytes prints `0%`, not `NaN` or `�`
   (Task 3).
4. A thread that ends after the snapshot, so `getThreadGroup()` is null, still
   prints with `group=-DEAD-` (Task 4).
5. A custom value that is null prints `null`, and callers may pass an
   unmodifiable map (Task 5).

## Rulings

- R1. The "Collector" header gets the same "used/committed, used/max" label as
  the "Memory" header, since both head `formatUsage` columns. Undo: one
  string.
- R2. The pool print order also lists the Java 9+ code heap pools after
  `Code Cache`, because mica's consumer runs on Java 17 and G1 reports them in
  every dump. Undo: remove three names and one test row.
- R3. When a thread has both a lock and a custom `toString()`, the suffixes
  are separated by one space instead of two. Undo: none needed.
- R4. The test task sets `user.language=en` and `user.country=US` so that
  formatted dates, time zone names, and numbers are stable. Undo: two lines.
- R5. `Durations` returns "Not Available" for any negative input, which
  covers the -1 that `RuntimeMXBean`, `OperatingSystemMXBean`, and
  `GarbageCollectorMXBean` return for unavailable values.

---

### Task 1: Test libraries, `Line`, `BytesUtils`, and `Durations`

**Files:**

- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`
- Modify: `generator/src/main/java/com/widen/util/td/Line.java`,
  `BytesUtils.java`, `JvmThreadDumpImpl.java` (calls to the duration
  formatter)
- Create: `generator/src/main/java/com/widen/util/td/Durations.java`
- Delete: `DurationFormatUtils.java`, `StringUtils.java`
- Test: `generator/src/test/java/com/widen/util/td/TestLine.java`,
  `TestBytesUtils.java`, `TestDurations.java`, `TestText.java` (helper)

**Interfaces:**

- Produces: `static String Line.render(List<? extends Line> lines)`;
  `Durations.daysHoursMinutesSeconds(long millis)` returning
  `"%d days, %d:%02d:%02d"`; `Durations.hoursMinutesSecondsMillis(long millis)`
  returning `"%d:%02d:%02d.%03d"` with unbounded hours; both return
  `"Not Available"` for negative input. Test helper `TestText` with
  `static List<String> render(List<? extends Line> lines)` (the rendered text
  split on `System.lineSeparator()`, without the final empty element),
  `static String titled(String title, String value)`
  (`String.format("%22s: %s", ...)`), and
  `static String continued(String value)`
  (`String.format("%22s  %s", "", value)`).

- [ ] **Step 1: Add Mockito and the test locale**

In `gradle/libs.versions.toml` add `mockito = "4.11.0"` and
`mockito-core = { module = "org.mockito:mockito-core", version.ref = "mockito" }`.
In the root `build.gradle.kts` `subprojects {}` block, add
`"testImplementation"(catalog.mockito.core)` to the dependencies, and in the
`tasks.withType<Test>().configureEach` block, which covers both `test` and
`testOnJava8`, add `systemProperty("user.language", "en")` and
`systemProperty("user.country", "US")` (R4). Mockito 4 uses the subclass mock
maker, which mocks interfaces and the non-final `ThreadInfo`.

- [ ] **Step 2: Write the failing tests**

```java
// TestLine
@Test void padsTitlesToTwentyTwoColumns() {
    assertThat(new Line.TitledLine("Heap", "x").toString())
            .isEqualTo("                  Heap: x" + System.lineSeparator());
    assertThat(new Line.TitledLine("Compressed Class Space*", "x").toString())
            .isEqualTo("Compressed Class Space*: x" + System.lineSeparator());
}
@Test void continuesMultiLineValuesUnderTheFirst() {
    assertThat(TestText.render(Arrays.asList(new Line.MultiLine("Props", Arrays.asList("a=1", "b=2")))))
            .containsExactly("                 Props: a=1", "                        b=2");
    assertThat(TestText.render(Arrays.asList(new Line.MultiLine("Props", Collections.emptyList()))))
            .containsExactly("                 Props: ");
}
@Test void rendersSimpleAndBlankLines() {
    assertThat(TestText.render(Arrays.asList(new Line.SimpleLine("abc"), new Line.BlankLine())))
            .containsExactly("abc", "");
}

// TestBytesUtils
@ParameterizedTest @CsvSource(delimiter = '|', value = {
    "0|0 bytes", "1023|1,023 bytes", "1024|1 KB", "1048575|1,023 KB",
    "1048576|1 MB", "4294967296|4,096 MB", "-1|-1 bytes"})
void formatsByteCountsAtUnitBoundaries(long bytes, String expected) {
    assertThat(BytesUtils.byteCountToDisplaySize(bytes)).isEqualTo(expected);
}

// TestDurations
@ParameterizedTest @CsvSource(delimiter = '|', value = {
    "0|0 days, 0:00:00", "90061000|1 days, 1:01:01",
    "345599000|3 days, 23:59:59", "-1|Not Available"})
void formatsUptimes(long millis, String expected) {
    assertThat(Durations.daysHoursMinutesSeconds(millis)).isEqualTo(expected);
}
@ParameterizedTest @CsvSource(delimiter = '|', value = {
    "0|0:00:00.000", "1234|0:00:01.234", "90061001|25:01:01.001", "-1|Not Available"})
void formatsCollectionTimesWithMillis(long millis, String expected) {
    assertThat(Durations.hoursMinutesSecondsMillis(millis)).isEqualTo(expected);
}
```

- [ ] **Step 3: Run them to verify they fail**

Run:
`mise exec -- ./gradlew :jvm-threaddump-generator:test -q --console=plain 2>&1 | tail -40`
Expected: compilation fails on `Line.render`, `TestText`, and `Durations`.

- [ ] **Step 4: Implement**
- `Line`: drop `public` from the interface and its nested classes and
  constructors; add `render`, which appends each line's `toString()`.
- `BytesUtils`: create `NumberFormat.getNumberInstance(Locale.US)` inside
  `byteCountToDisplaySize` (bug fix 5); remove the static field.
- `Durations`: package-private final class with the two methods above.
- `JvmThreadDumpImpl`: `formatTime` calls `Durations.daysHoursMinutesSeconds`
  after its unit conversion, and `doGc` calls
  `Durations.hoursMinutesSecondsMillis` (the GC-time fix). Delete
  `DurationFormatUtils` and `StringUtils`.
- [ ] **Step 5: Run the module tests**

Run:
`mise exec -- ./gradlew :jvm-threaddump-generator:test :jvm-threaddump-generator:testOnJava8 -q --console=plain 2>&1 | tail -40; echo "exit=${PIPESTATUS[0]}"`
Expected: `exit=0`, with the new tests and the existing `TestDump`,
`TestCustom`, and `TestDeadlock` passing.

- [ ] **Step 6: Commit**

`git commit -m "refactor: replace the commons duration formatter and test helpers"`
with a body explaining the per-call `NumberFormat`, millisecond GC times, and
the test locale.

### Task 2: `JvmSources`, `FakeJvm`, and `JvmInfoSection`

**Files:**

- Create: `generator/src/main/java/com/widen/util/td/JvmSources.java`,
  `PlatformJvmSources.java`, `JvmInfoSection.java`
- Modify: `JvmThreadDumpImpl.java` (hold a `JvmSources`; `doJvmInfo` and
  `doJvmMemory`'s argument reads go through it)
- Test: `TestJvmInfoSection.java`, `FakeJvm.java` (helper)

**Interfaces:**

- Consumes: `TestText`, `Durations`, `BytesUtils`, `Line` (Task 1).
- Produces:

```java
interface JvmSources
{
    RuntimeMXBean runtime();
    MemoryMXBean memory();
    ThreadMXBean threads();
    List<MemoryPoolMXBean> memoryPools();
    List<GarbageCollectorMXBean> garbageCollectors();
    java.lang.management.OperatingSystemMXBean operatingSystem();
    Clock clock();
    TimeZone timeZone();
    String environmentVariable(String name);   // null when unset
    String systemProperty(String name);        // null when unset
    long freeMemory();                         // Runtime.freeMemory()
    long maxMemory();                          // Runtime.maxMemory()
    Optional<String> procUptime();             // contents of /proc/uptime; empty when unreadable
    Set<Thread> liveThreads();                 // Thread.getAllStackTraces().keySet()

    static JvmSources platform() { return new PlatformJvmSources(); }
}

final class JvmInfoSection
{
    JvmInfoSection(JvmSources jvm, List<String> mainArguments, Map<String, ?> customValues)
    List<Line> lines()
    static String maskSecret(String property)  // property without the "-D" prefix
}
```

`FakeJvm implements JvmSources` (test helper) exposes package-private mutable
fields named after the accessors, with these defaults; tests change fields
before building a section. Other tasks' tests reuse it.

| Field | Default |
| --- | --- |
| `runtime` | mock; `getInputArguments()` = `["-Dfile.encoding=UTF-8", "-Xmx512m"]`, `getUptime()` = `90_061_000` |
| `memory` | mock; heap `new MemoryUsage(256 MB, 64 MB, 128 MB, 256 MB)`, non-heap `new MemoryUsage(2 MB, 8 MB, 16 MB, -1)` |
| `threads` | mock; `findDeadlockedThreads()` = null, `dumpAllThreads(false, false)` = `new ThreadInfo[0]`, CPU time unsupported |
| `memoryPools`, `garbageCollectors` | empty `ArrayList`s |
| `operatingSystem` | mock of `com.sun.management.OperatingSystemMXBean`: name `Linux`, version `5.10`, 4 processors, arch `amd64`, free physical `1 GB`, total physical `4 GB`, system CPU load `0.25`, process CPU load `0.125`, process CPU time `61_000_000_000` ns |
| `clock` | `Clock.fixed(Instant.parse("2026-11-20T15:30:00Z"), ZoneOffset.UTC)` |
| `timeZone` | `TimeZone.getTimeZone("UTC")` |
| `environment` | `HashMap` with `HOSTNAME=app-1` |
| `systemProperties` | `HashMap`: `java.runtime.version=1.8.0_472-b08`, `java.vendor=Example Vendor`, `java.vm.version=25.472-b08`, `java.vm.info=mixed mode`, `java.home=/opt/java`, `user.dir=/srv/app`, `java.io.tmpdir=/tmp`, `file.encoding=UTF-8` |
| `freeMemory`, `maxMemory` | `100 MB`, `512 MB` |
| `procUptime` | `Optional.of("93784.56 12345.67\n")` |
| `liveThreads` | empty `HashSet` |

- [ ] **Step 1: Write the failing tests**

```java
@Test void printsJvmAndHostInfo() {
    assertThat(TestText.render(new JvmInfoSection(new FakeJvm(), emptyList(), emptyMap()).lines()))
            .containsExactly(
                    titled("Current Time", "11/20/2026 15:30:00 UTC"),
                    titled("Default Time Zone", "Coordinated Universal Time (UTC)"),
                    titled("Java Version", "1.8.0_472-b08 (Example Vendor)"),
                    titled("Java VM", "25.472-b08 (mixed mode)"),
                    titled("Java Home", "/opt/java"),
                    titled("Working Directory", "/srv/app"),
                    titled("Temp Directory", "/tmp"),
                    "",
                    titled("Main Arguments", ""),
                    titled("Default Encoding", "UTF-8"),
                    titled("JVM System Props", "file.encoding=UTF-8"),
                    "",
                    titled("Hostname", "app-1"),
                    titled("Operating System", "Linux (5.10)"),
                    titled("System Processors", "4 amd64"),
                    titled("System Memory", "1,024 MB available; 4,096 MB total"),
                    titled("CPU Load", "0.2500 system; 0.1250 jvm (0.0 to 1.0)"),
                    titled("System Uptime", "1 days, 2:03:04"),
                    titled("System Idle Time", "0 days, 3:25:45"),
                    titled("JVM Uptime", "1 days, 1:01:01"),
                    titled("JVM CPU Time", "0 days, 0:01:01"),
                    "");
}

@Test void printsCustomValuesAndMainArgumentsInOrder()
// mainArguments ["--port", "8080"], customValues LinkedHashMap {App=cheese, Build=42}:
// after the first "" come titled("App", "cheese"), titled("Build", "42"), "",
// then titled("Main Arguments", "--port 8080").

@ParameterizedTest @ValueSource(strings = {"", "garbage", "12.5"})
void printsNotAvailableForMissingHostData(String uptime)
// operatingSystem = mock(java.lang.management.OperatingSystemMXBean.class) with the
// same name/version/processors/arch; environment cleared; procUptime =
// "" -> Optional.empty(), otherwise Optional.of(uptime). Lines for Hostname, System Memory,
// CPU Load, System Uptime, System Idle Time, and JVM CPU Time read "Not Available".

@ParameterizedTest @CsvSource(delimiter = '|', value = {
    "db.password=hunter2|db.password=****", "API_SECRET=abc|API_SECRET=****",
    "auth.Token=abc|auth.Token=****", "aws.credentials.file=/x|aws.credentials.file=****",
    "javax.net.ssl.keyStore=/k|javax.net.ssl.keyStore=****", "monkey=banana|monkey=****",
    "api.key=|api.key=****", "token=a=b|token=****",
    "jdbc.url=jdbc:postgresql://db/app?user=app&password=x|jdbc.url=****",
    "app.opts=retries=3;Token=abc|app.opts=****",
    "app.url=https://example.com/?page=2|app.url=https://example.com/?page=2",
    "app.note=password|app.note=password",
    "secret.flag|secret.flag", "file.encoding=UTF-8|file.encoding=UTF-8"})
void masksSecretPropertyValues(String property, String expected)

@Test void sortsSystemPropertiesAfterMasking()
// input arguments ["-Dz.token=abc", "-Da=1", "-Xmx1g", "-javaagent:x.jar"]:
// titled("JVM System Props", "a=1") followed by continued("z.token=****").
```

- [ ] **Step 2: Run them to verify they fail**

Run:
`mise exec -- ./gradlew :jvm-threaddump-generator:test --tests 'com.widen.util.td.TestJvmInfoSection' -q --console=plain 2>&1 | tail -40`
Expected: compilation fails on `JvmSources`, `FakeJvm`, and `JvmInfoSection`.

- [ ] **Step 3: Implement**
- `PlatformJvmSources`: reads `ManagementFactory` and `Runtime.getRuntime()`
  on each call; `clock()` is `Clock.systemDefaultZone()`, `timeZone()` is
  `TimeZone.getDefault()`; `procUptime()` reads `/proc/uptime` as US-ASCII and
  returns `Optional.empty()` on any `IOException`.
- `JvmInfoSection`: moves `doJvmInfo` from `JvmThreadDumpImpl`, reading every
  value through `JvmSources`. Current Time uses a new
  `SimpleDateFormat("MM/dd/yyyy HH:mm:ss z")` with `setTimeZone(timeZone)` on
  `Date.from(clock.instant())`. The `com.sun.management.OperatingSystemMXBean`
  extras are read only after an `instanceof` check; otherwise System Memory, CPU
  Load, and JVM CPU Time print "Not Available". `/proc/uptime` content that does
  not split into two numbers prints "Not Available" for both lines. `maskSecret`
  splits at the first `=`; a key that contains `password`, `secret`, `token`,
  `credential`, or `key`, ignoring case, or a value that contains one of those
  words followed by `=`, ignoring case, keeps the key and prints `****` as the
  value (bug fix 2). System Props collects the masked values into a `TreeSet`.
- `JvmThreadDumpImpl`: add a package-private constructor taking `JvmSources`;
  the no-arg constructor passes `JvmSources.platform()`. Replace the static
  bean fields with reads from the held `JvmSources`, and replace `doJvmInfo`
  with `new JvmInfoSection(jvm, getMainArguments(), getCustomValues())`.
- [ ] **Step 4: Run the module tests**

Run:
`mise exec -- ./gradlew :jvm-threaddump-generator:test :jvm-threaddump-generator:testOnJava8 -q --console=plain 2>&1 | tail -40; echo "exit=${PIPESTATUS[0]}"`
Expected: `exit=0`.

- [ ] **Step 5: Commit**

`git commit -m "refactor: read jvm info through injectable sources and mask secrets"`

### Task 3: `MemorySection`, `GcSection`, and `DeadlockSection`

**Files:**

- Create: `MemorySection.java`, `GcSection.java`, `DeadlockSection.java` in
  `generator/src/main/java/com/widen/util/td/`
- Modify: `JvmThreadDumpImpl.java` (replace `doJvmMemory`, `doGc`,
  `doDeadlocks`)
- Test: `TestMemorySection.java`, `TestGcSection.java`,
  `TestDeadlockSection.java`

**Interfaces:**

- Consumes: `JvmSources`, `FakeJvm` (Task 2); `TestText`, `Durations` (Task 1).
- Produces: `MemorySection(JvmSources)`, `GcSection(JvmSources)`, and
  `DeadlockSection(JvmSources)`, each with `List<Line> lines()`;
  `static String MemorySection.formatUsage(MemoryUsage usage)`; and
  `static final List<String> MemorySection.POOL_ORDER`:

```java
"PS Eden Space", "Par Eden Space", "Par Survivor Space", "PS Survivor Space",
"PS Old Gen", "CMS Old Gen", "G1 Eden Space", "G1 Survivor Space", "G1 Old Gen",
"Eden Space", "Survivor Space", "Tenured Gen", "Code Cache",
"CodeHeap 'non-nmethods'", "CodeHeap 'profiled nmethods'",
"CodeHeap 'non-profiled nmethods'", "Compressed Class Space", "Metaspace"
```

- [ ] **Step 1: Write the failing tests**

`formatUsage` keeps the format string
`%-12s  init=%-10s  used=%-10s  commit=%-10s  max=%-10s`. If a literal below
disagrees with that format, fix the literal, not the format.

```java
@ParameterizedTest @MethodSource
void formatsUsageAsPercentsAndSizes(MemoryUsage usage, String expected)
// new MemoryUsage(256 MB, 64 MB, 128 MB, 256 MB)
//   -> "50%/25%       init=256 MB      used=64 MB       commit=128 MB      max=256 MB    "
// new MemoryUsage(2 MB, 8 MB, 16 MB, -1)
//   -> "50%/0%        init=2 MB        used=8 MB        commit=16 MB       max=-1 bytes  "
// new MemoryUsage(0, 0, 0, 1 MB)
//   -> "0%/0%         init=0 bytes     used=0 bytes     commit=0 bytes     max=1 MB      "

@Test void printsHeapSummaryAndPools()
// FakeJvm defaults plus pools [Metaspace, "Some Pool", G1 Old Gen, "Another Pool",
// G1 Eden Space, G1 Survivor Space whose getUsage() is null]:
// titled("JVM Free Memory", "100 MB (of committed)"), titled("JVM Maximum Heap", "512 MB"),
// titled("JVM Memory Args", "-Xmx512m"), "",
// titled("Memory", "used/committed, used/max"), titled("Heap", formatUsage(heap)),
// titled("Non Heap", formatUsage(nonHeap)), "",
// titled("Collector", "used/committed, used/max"),
// titled("G1 Eden Space", ...), titled("G1 Old Gen", ...), titled("Metaspace", ...),
// titled("Another Pool*", ...), titled("Some Pool*", ...), "".

@ParameterizedTest @MethodSource
void printsKnownPoolsInCollectorOrder(List<String> reported, List<String> printed)
// Parallel [PS Old Gen, PS Survivor Space, PS Eden Space] -> Eden, Survivor, Old;
// CMS [CMS Old Gen, Par Survivor Space, Par Eden Space] -> Par Eden, Par Survivor, CMS Old Gen;
// G1 [G1 Old Gen, G1 Survivor Space, G1 Eden Space] -> Eden, Survivor, Old;
// Serial [Tenured Gen, Survivor Space, Eden Space] -> Eden Space, Survivor Space, Tenured Gen;
// Java 17 non-heap [Metaspace, CodeHeap 'non-profiled nmethods', Compressed Class Space,
//   CodeHeap 'profiled nmethods', CodeHeap 'non-nmethods'] -> POOL_ORDER order.
// Compares only the pool titles between the "Collector" line and the final "".

@Test void printsUnlimitedMaximumHeap()   // maxMemory = Long.MAX_VALUE -> "unlimited"

// TestGcSection
@Test void printsEachCollector()
// gc("G1 Young Generation", 12 collections, 1234 ms), gc("G1 Old Generation", 0, 0):
// titled("GC G1 Young Generation", "spent 0:00:01.234 doing 12 collections"),
// titled("GC G1 Old Generation", "spent 0:00:00.000 doing 0 collections"), "".
@Test void printsNotAvailableForUndefinedCollectionTime()
// time -1, count 3 -> "spent Not Available doing 3 collections"

// TestDeadlockSection
@Test void printsNoneWithoutDeadlocks()     // [titled("Deadlocked Threads", "None")]
@Test void printsDeadlockedIdsInHex()       // {3, 26} -> [titled("Deadlocked Threads", "0x3, 0x1a")]
```

- [ ] **Step 2: Run them to verify they fail**

Run:
`mise exec -- ./gradlew :jvm-threaddump-generator:test --tests 'com.widen.util.td.Test*Section' -q --console=plain 2>&1 | tail -40`
Expected: compilation fails on the three section classes.

- [ ] **Step 3: Implement**

Move the three `do*` methods into the sections. `formatUsage` uses a
`NumberFormat.getPercentInstance()` created per call, and prints `0%` for a
ratio whose denominator is zero or negative. `MemorySection` skips a pool
whose `getUsage()` is null (bug fix 6), prints known pools in `POOL_ORDER`
(bug fix 8, R2), then unlisted pools in name order with a `*` suffix. Both
header lines read `used/committed, used/max` (R1). In `JvmThreadDumpImpl`,
replace each `do*` method with the matching section's lines.

- [ ] **Step 4: Run the module tests**

Run:
`mise exec -- ./gradlew :jvm-threaddump-generator:test :jvm-threaddump-generator:testOnJava8 -q --console=plain 2>&1 | tail -40; echo "exit=${PIPESTATUS[0]}"`
Expected: `exit=0`. `TestDump` asserts `"Memory: used/max"`; change it to
`"Memory: used/committed, used/max"`.

- [ ] **Step 5: Commit**

`git commit -m "refactor: split memory, gc, and deadlock sections and fix pools"`

### Task 4: `ThreadSection`

**Files:**

- Create: `generator/src/main/java/com/widen/util/td/ThreadSection.java`
- Modify: `JvmThreadDumpImpl.java` (replace `doThreads`)
- Delete: `ThreadDumpProducer.java`
- Test: `TestThreadSection.java`, `FakeThreads.java` (helper)

**Interfaces:**

- Consumes: `JvmSources`, `FakeJvm` (Task 2); `TestText` (Task 1).
- Produces: `ThreadSection(JvmSources)` with `List<Line> lines()`;
  `static String ThreadSection.formatFrame(StackTraceElement frame)`;
  `static String ThreadSection.formatCpuTime(long nanos)`. Test helper
  `FakeThreads` with `static final ThreadGroup GROUP` named `test-group`;
  `static Thread thread(String name, long id)` (unstarted, in `GROUP`, `getId()`
  overridden to return `id`);
  `static Thread thread(String name, long id, String toString)` (also overrides
  `toString()`); and
  `static ThreadInfo info(long id, Thread.State state, StackTraceElement... frames)`
  (a Mockito mock with lock name null, lock owner -1, and zero blocked and
  waited counts; tests stub more as needed).
- [ ] **Step 1: Write the failing tests**

```java
@Test void printsCountHeaderAndFrames()
// one thread "worker-1" id 23 priority 5, info WAITING, blocked 1, waited 2,
// lock "java.util.concurrent.locks.ReentrantLock$NonfairSync@1b6d3586" owned by 31,
// frames Worker.run(Worker.java:42) and Thread.run(Thread.java:750):
// titled("Thread Count", "1"), "",
// "\"worker-1\" priority=5 id=0x17 group=test-group cpu=-1 block_cnt=1 wait_cnt=2 WAITING"
//     + " (waiting on 0x1f for java.util.concurrent.locks.ReentrantLock$NonfairSync@1b6d3586)",
// "    com.example.Worker.run(Worker.java:42)", "    java.lang.Thread.run(Thread.java:750)", "".

@ParameterizedTest @MethodSource
void formatsThreadHeaders(Thread thread, ThreadInfo info, String header)
// daemon "pool-timer" id 40 priority 8, TIMED_WAITING, lock "java.lang.Object@4554617c", no owner
//   -> "\"pool-timer\" daemon priority=8 id=0x28 group=test-group cpu=-1 block_cnt=0 wait_cnt=0 TIMED_WAITING (waiting for java.lang.Object@4554617c)"
// "main" id 1 with toString "custom text", RUNNABLE, no lock
//   -> "\"main\" priority=5 id=0x1 group=test-group cpu=-1 block_cnt=0 wait_cnt=0 RUNNABLE (custom text)"
// "worker" id 2 with toString "worker", RUNNABLE -> no toString suffix
// "blocked" id 3, BLOCKED, lock "java.lang.Object@1" owned by 4, toString "busy"
//   -> "... BLOCKED (waiting on 0x4 for java.lang.Object@1) (busy)"

@Test void ordersThreadsByNameIgnoringCaseThenNameThenId()
// threads worker-1/23, worker-12/3, Worker/12, worker1/2, b/5, A/6, a/7, pool/9, pool/8,
// each with a RUNNABLE info: titled("Thread Count", "9") and headers in the order
// A, a, b, pool (0x8), pool (0x9), Worker, worker-1, worker-12, worker1.

@Test void printsOnlyThreadsInBothSnapshots()
// Thread "gone" id 50 without an info, an info id 60 without a Thread, and "kept" id 61
// with both: titled("Thread Count", "1"), and only "kept" prints.

@Test void printsDeadForAThreadThatEndedAfterTheSnapshot()
// start and join a FakeThreads thread, then give it an info: its header has group=-DEAD-.

@Test void printsCpuTimeWhenEnabled()
// threads mock: isThreadCpuTimeSupported() and isThreadCpuTimeEnabled() true,
// getThreadCpuTime(23) = 1_500_000_000 -> header contains " cpu=1500ms ".

@ParameterizedTest @CsvSource(delimiter = '|', value = {
    "-1|-1", "0|0ms", "9999999999|9999ms", "10000000000|10s"})
void formatsCpuTime(long nanos, String expected)

@ParameterizedTest @MethodSource
void formatsFrames(StackTraceElement frame, String expected)
// ("com.example.Worker", "run", "Worker.java", 42) -> "    com.example.Worker.run(Worker.java:42)"
// ("com.example.Worker", "run", "Worker.java", -1) -> "    com.example.Worker.run(Worker.java)"
// ("java.lang.Object", "wait", null, -2)           -> "    java.lang.Object.wait(Native Method)"
// ("com.example.Gen", "call", null, -1)            -> "    com.example.Gen.call(Unknown Source)"
```

- [ ] **Step 2: Run them to verify they fail**

Run:
`mise exec -- ./gradlew :jvm-threaddump-generator:test --tests 'com.widen.util.td.TestThreadSection' -q --console=plain 2>&1 | tail -40`
Expected: compilation fails on `ThreadSection` and `FakeThreads`.

- [ ] **Step 3: Implement**

`lines()` calls `jvm.threads().dumpAllThreads(false, false)` once, indexes the
infos by thread id, and keeps each thread in `jvm.liveThreads()` whose
`getId()` has an info. It reads each thread's name and `toString()` once and
sorts the snapshot by name ignoring case, then name, then id (bug fix 1).
State, counts, lock, and frames come from the info; the name and
`toString()` read once, priority, daemon, and group come from the thread (bug
fix 9).
The header format is

```java
"\"%s\"%s priority=%d id=0x%x group=%s cpu=%s block_cnt=%d wait_cnt=%d %s"
```

followed by the lock suffix (` (waiting on 0x%x for %s)` with an owner and a
lock name, `(waiting on 0x%x)` with an owner only, `(waiting for %s)` with a
lock name only) and the `toString()` suffix (` (%s)` unless it starts with
`Thread[` or equals the name ignoring case). A null thread group prints
`-DEAD-`. CPU time reads `getThreadCpuTime(id)` only when CPU time is
supported and enabled, otherwise -1. Thread Count is the number of joined
threads. Replace `doThreads` in `JvmThreadDumpImpl` and delete
`ThreadDumpProducer`.

- [ ] **Step 4: Run the module tests**

Run:
`mise exec -- ./gradlew :jvm-threaddump-generator:test :jvm-threaddump-generator:testOnJava8 -q --console=plain 2>&1 | tail -40; echo "exit=${PIPESTATUS[0]}"`
Expected: `exit=0`.

- [ ] **Step 5: Commit**

`git commit -m "fix: order and snapshot threads consistently in the dump"`

### Task 5: `JvmThreadDump` builder API

**Files:**

- Modify: `generator/src/main/java/com/widen/util/td/JvmThreadDump.java`
  (interface becomes a final class)
- Delete: `JvmThreadDumpImpl.java`, `TestCustom.java`
- Modify: `TestDump.java`, `TestDeadlock.java`
- Test: `TestJvmThreadDump.java`

**Interfaces:**

- Consumes: the five sections and `FakeJvm` (Tasks 2 to 4).
- Produces:

```java
public final class JvmThreadDump
{
    public static JvmThreadDump create()            // builder().build()
    public static Builder builder()
    public String generate()

    public static final class Builder
    {
        public Builder mainArguments(List<String> mainArguments)   // copied at build()
        public Builder customValues(Map<String, ?> customValues)   // copied at build(), order kept
        Builder jvmSources(JvmSources jvm)                         // package-private; tests
        public JvmThreadDump build()
    }
}
```

- [ ] **Step 1: Write the failing tests**

```java
// TestJvmThreadDump, with FakeJvm through builder().jvmSources(fake)
@Test void rendersSectionsInOrder()
// generate() equals Line.render of JvmInfoSection, MemorySection, GcSection,
// DeadlockSection, and ThreadSection lines, concatenated in that order, for one FakeJvm.
@Test void returnsTheSameDumpOnRepeatCalls()            // bug fix 4: two generate() calls are equal
@Test void printsMainArgumentsAndCustomValuesInMapOrder()
// mainArguments ["--port", "8080"]; LinkedHashMap {Zeta=1, Alpha=null}: the dump contains
// titled("Zeta", "1") before titled("Alpha", "null"), and titled("Main Arguments", "--port 8080").
@Test void ignoresChangesToTheCallersCollectionsAfterBuild()
// mutate the list and map after build(); the dump matches one built from the original values.
@Test void acceptsUnmodifiableCustomValues()             // Collections.singletonMap("App", "cheese")

// TestDump (real JVM)
@Test void printsEverySectionOfTheRunningJvm()
// JvmThreadDump.create().generate() contains "Current Time: ", "JVM Free Memory: ",
// "Memory: used/committed, used/max", "GC ", "Deadlocked Threads: None",
// "Thread Count: ", and "\"Test worker\"" (Gradle's test thread, which proves the
// thread join works against the real ThreadMXBean).
```

`TestDeadlock` keeps its assertions and calls `JvmThreadDump.create()`.

- [ ] **Step 2: Run them to verify they fail**

Run:
`mise exec -- ./gradlew :jvm-threaddump-generator:test -q --console=plain 2>&1 | tail -40`
Expected: compilation fails on `JvmThreadDump.builder()`.

- [ ] **Step 3: Implement**

`build()` copies into `Collections.unmodifiableList(new ArrayList<>(...))` and
`Collections.unmodifiableMap(new LinkedHashMap<>(...))`, and defaults to
empty collections and `JvmSources.platform()`. `generate()` builds a local
`List<Line>` from the five sections and returns `Line.render`. Null builder
arguments throw `NullPointerException` via `Objects.requireNonNull`. Add
JavaDoc to the public class, the builder, and each public method: thread
safety, when `String.valueOf` runs on custom values (on each `generate()`),
and the copy at `build()`. Delete `JvmThreadDumpImpl` and `TestCustom`.

- [ ] **Step 4: Run the module tests**

Run:
`mise exec -- ./gradlew :jvm-threaddump-generator:test :jvm-threaddump-generator:testOnJava8 -q --console=plain 2>&1 | tail -40; echo "exit=${PIPESTATUS[0]}"`
Expected: `exit=0`.

- [ ] **Step 5: Commit**

`git commit -m "feat!: configure the dump with a builder instead of subclass hooks"`
with a body that names the removed `JvmThreadDumpImpl` and hooks and the builder
that replaces them.

### Task 6: `ThreadDumpServlet`

**Files:**

- Modify: `servlet/src/main/java/com/widen/util/td/ThreadDumpServlet.java`,
  `servlet/build.gradle.kts`
- Test: `servlet/src/test/java/com/widen/util/td/TestThreadDumpServlet.java`

**Interfaces:**

- Consumes: `JvmThreadDump` (Task 5).
- Produces: `public ThreadDumpServlet()` (uses `JvmThreadDump.create()`) and
  `public ThreadDumpServlet(JvmThreadDump dump)`; `doGet` stays `protected`.
- [ ] **Step 1: Add the test dependency**

Add `testImplementation(libs.servlet.api)` to `servlet/build.gradle.kts`
(deferred item E1 from layer 1).

- [ ] **Step 2: Write the failing tests**

The response mock returns a `ServletOutputStream` subclass that writes to a
`ByteArrayOutputStream` (servlet 3.0.1's only abstract method is
`write(int)`).

```java
@Test void servesTheDumpAsUtf8Text()
// new ThreadDumpServlet(JvmThreadDump.builder().customValues(singletonMap("Owner", "Zoë ✓")).build())
// .doGet(request, response): verify setContentType("text/plain; charset=UTF-8");
// the written bytes decode as UTF-8 to text containing "Owner: Zoë ✓"; setContentLength
// equals the written byte count, which exceeds the decoded text's length;
// headers Cache-Control "no-cache, no-store, must-revalidate, max-age=0",
// Expires "Thu, 01 Jan 1970 12:00:00 +0000", X-Accel-Expires "off",
// X-Robots-Tag "noindex, nofollow"; and getWriter() is never called.

@Test void servesValuesFromASubclassLikeMicas()
// a static nested copy of the servlet mica will write:
//   static class MicaThreadDumpServlet extends ThreadDumpServlet {
//       MicaThreadDumpServlet(Map<String, Object> contribution, String appName, String env) {
//           super(JvmThreadDump.builder()
//                   .mainArguments(Arrays.asList("--app", appName))
//                   .customValues(values(contribution, appName, env))   // App, Environment, then contribution
//                   .build());
//       }
//   }
// GET with contribution {Region=us-east-1}, "cheese", "test": the body contains
// "App: cheese", "Environment: test", "Region: us-east-1", and "Main Arguments: --app cheese".

@Test void servesThePlatformDumpByDefault()     // new ThreadDumpServlet() body contains "Thread Count: "
```

- [ ] **Step 3: Run them to verify they fail**

Run:
`mise exec -- ./gradlew :jvm-threaddump-servlet:test -q --console=plain 2>&1 | tail -40`
Expected: compilation fails on the `ThreadDumpServlet(JvmThreadDump)`
constructor.

- [ ] **Step 4: Implement**

Hold the dump in a `private final` field. `doGet` encodes `generate()` as
UTF-8, sets `text/plain; charset=UTF-8`, sets the content length to the byte
count, sets the four headers, and writes the bytes to `getOutputStream()`
(bug fix 3). Remove the `init` override and the comment about custom
implementations. Add JavaDoc to the class and both constructors.

- [ ] **Step 5: Run the module tests**

Run:
`mise exec -- ./gradlew test testOnJava8 -q --console=plain 2>&1 | tail -40; echo "exit=${PIPESTATUS[0]}"`
Expected: `exit=0` for both modules.

- [ ] **Step 6: Commit**

`git commit -m "fix(servlet): serve a configured dump as utf-8 bytes"`

### Task 7: README and final checks

**Files:**

- Modify: `readme.md`

- [ ] **Step 1: Rewrite the Usage, Customization, and Glossary sections**

Show `JvmThreadDump.create().generate()`, a builder example with
`mainArguments` and `customValues`, the `web.xml` registration (unchanged),
and a subclass of `ThreadDumpServlet` that passes a built dump to `super`, in
the shape of `MicaThreadDumpServlet` from Task 6 (bug fix 10, deferred item
E2). State that the instance is immutable and safe to share, and that custom
values are formatted with `String.valueOf` on each `generate()`. Remove the
advice to copy the servlet. In the Glossary, `Main Arguments` is empty unless
the builder sets `mainArguments`, and add one line saying `-D` values whose
keys contain `password`, `secret`, `token`, `credential`, or `key`, or whose
values contain one of those words followed by `=`, print as
`****`. In Example Output, change both header values to
`used/committed, used/max` and the GC lines to `0:00:00.000`.

Use `java` instead of `text` for the Java fences. This is more than 30 lines
of prose, so the controller has a `sonnet` subagent copyedit it with the
`elements-of-style:writing-clearly-and-concisely` skill before committing.

- [ ] **Step 2: Verify the examples compile**

Copy each Java example into a scratch test class in the servlet module, compile
with
`mise exec -- ./gradlew :jvm-threaddump-servlet:compileTestJava -q --console=plain`,
then delete the scratch class. Expected: no compile errors.

- [ ] **Step 3: Verify the build**

Run:
`mise exec -- ./gradlew clean build publishToMavenLocal -q --console=plain 2>&1 | tail -40; echo "exit=${PIPESTATUS[0]}"`
Expected: `exit=0`, and
`od -An -j6 -N2 -tu1 generator/build/classes/java/main/com/widen/util/td/JvmThreadDump.class`
prints `0 52`.

- [ ] **Step 4: Commit**

`git commit -m "docs(readme): document the builder and servlet constructor"`
