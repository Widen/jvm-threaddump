# CPU Limit Line and CPU Throttled Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Print a container's CPU limit and how often the kernel throttled it,
and flag frequent throttling with a tenth check.

**Architecture:** `JvmSources` gains one method that reads a file, which tests
fake with a map. `CgroupCpu` reads the limit and the throttling counters from
the cgroup v2 or v1 files at fixed container paths. `JvmSnapshot` reads a
`CgroupCpu` at each end of the sampling window and holds the totals and the
window's difference. `JvmInfoSection` prints the `CPU Limit` line and
`CpuThrottledCheck` flags it.

**Tech Stack:** Gradle 9.9.2 (Kotlin DSL), Java 17 toolchain with all code
compiled at `options.release = 8`, tests run by `test` (Java 17) and
`testOnJava8`, JUnit Jupiter 5.14.4, AssertJ 3.27.7, Mockito 4.11.0.

**Spec:** `docs/superpowers/specs/2026-11-21-cpu-throttled-design.md`
**Issue:** <https://github.com/Widen/jvm-threaddump/issues/9>

## Global Constraints

- All code, tests included, compiles with `options.release = 8`: no Java 9+ API
  (no `Files.readString`, `List.of`, `Map.of`, `var`, `Optional.isEmpty`).
- All main and test classes are in
  `generator/src/{main,test}/java/com/widen/util/td/`, package
  `com.widen.util.td`. Paths below are relative to those two directories.
- Match the surrounding style: class brace on its own line, package-private
  classes and fields, `Locale.US` in every `String.format`, a Javadoc only where
  it says what the code does not.
- A missing, unreadable, or malformed cgroup file never throws out of
  `generate()`. The dump then has no CPU limit.
- Run a task's tests with
  `mise exec -- ./gradlew :jvm-threaddump-generator:test --tests com.widen.util.td.CLASS -q --console=plain > SCRATCH/gradle.txt 2>&1`,
  then read the file: no output means they passed. Before each commit, run
  `mise exec -- ./gradlew :jvm-threaddump-generator:test :jvm-threaddump-generator:testOnJava8 -q --console=plain`
  the same way. Both take under 30 seconds.
- Never run Spotless, `check`, or `build`.
- Commit messages are conventional commits with no trailers.
- After editing `README.md`, run
  `mise exec rumdl -- rumdl fmt --flavor gfm --config 'MD013.reflow = true' README.md`.

## Review Focus

1. A real `cpu.stat` has other lines around the two counters, in a different
   order per cgroup version (`usage_usec` first on v2, `throttled_time` last on
   v1). The counters are found by name. Task 1's table uses a full v2 file and a
   full v1 file.
2. The files end with a newline, and `cpu.max` may hold a quota below its period
   (`50000 100000`). A limit of half a CPU prints as `0.50 CPUs`. Tasks 1 and 3.
3. The limit is present at the window's start and gone at its end, or the
   reverse (a file vanishes, or an operator removes the limit). The snapshot
   then follows the end reading and its window counts are 0. Task 2.
4. A counter is lower at the window's end than at its start. The window counts
   are 0, never negative. Task 2.
5. One throttled period out of one, in a nearly idle container, does not flag.
   Task 4's table.

---

### Task 1: Read the CPU limit and throttling counters

**Files:**

- Create: `CgroupCpu.java`, `TestCgroupCpu.java`
- Modify: `JvmSources.java`, `PlatformJvmSources.java`, `JvmInfoSection.java`
  (`systemUptimeSeconds`), `FakeJvm.java`, `TestJvmInfoSection.java:113`

**Interfaces:**

- Produces, on `JvmSources`, replacing `procUptime()`:

  ```java
  /** @return the file's text, or empty when the file is missing or unreadable */
  Optional<String> file(String path);
  ```

- Produces:

  ```java
  /** The CPU limit of the container and the kernel's count of the periods it was throttled in. */
  final class CgroupCpu
  {
      final double limitCpus;        // quota / period, above zero
      final long periods;            // nr_periods
      final long throttledPeriods;   // nr_throttled

      /** Reads cgroup v2, then v1. Returns null when the container has no limit or a file does not parse. */
      static CgroupCpu read(JvmSources jvm)
  }
  ```

- Produces, on `FakeJvm`: `Map<String, String> files = new HashMap<>()`, preset
  with `/proc/uptime` holding `"93784.56 12345.67\n"`, which `file(path)` reads;
  and

  ```java
  /** Puts cgroup v2 files in {@link #files}: a quota per 100 ms period and the two counters. */
  void cgroupV2(long quotaMicros, long periods, long throttledPeriods)
  ```

The paths, tried in this order; the first directory whose limit file is readable
decides, and no later one is tried:

| Order | Limit file | Counters |
| --- | --- | --- |
| 1 | `/sys/fs/cgroup/cpu.max` | `/sys/fs/cgroup/cpu.stat` |
| 2 | `/sys/fs/cgroup/cpu/cpu.cfs_quota_us` and `cpu.cfs_period_us` | `/sys/fs/cgroup/cpu/cpu.stat` |
| 3 | `/sys/fs/cgroup/cpu,cpuacct/cpu.cfs_quota_us` and `cpu.cfs_period_us` | `/sys/fs/cgroup/cpu,cpuacct/cpu.stat` |

- [ ] **Step 1: Replace `procUptime()` with `file(String path)`.**
      `PlatformJvmSources.file` keeps the `Files.readAllBytes` body and its
      `IOException` to empty, and decodes as `US_ASCII`.
      `JvmInfoSection.systemUptimeSeconds` calls `jvm.file("/proc/uptime")`.
      `FakeJvm` drops its `procUptime` field for the `files` map.
      `TestJvmInfoSection:113` removes or puts `/proc/uptime` in `jvm.files`.

- [ ] **Step 2: Run `TestJvmInfoSection`.** Expected: pass, with no test changed
      beyond line 113.

- [ ] **Step 3: Write the failing test `TestCgroupCpu`.**

  ```java
  private static final String V2_STAT = "usage_usec 2844155\nuser_usec 2668749\nsystem_usec 175406\n"
          + "nice_usec 0\nnr_periods 42\nnr_throttled 21\nthrottled_usec 2789108\nnr_bursts 0\nburst_usec 0\n";
  private static final String V1_STAT = "nr_periods 42\nnr_throttled 21\nthrottled_time 2789108000\n";

  static Stream<Arguments> readsTheLimitAndCounters() {
      return Stream.of(
              arguments("v2", files("/sys/fs/cgroup/cpu.max", "100000 100000\n", "/sys/fs/cgroup/cpu.stat", V2_STAT), 1.0),
              arguments("v2 half a CPU", files("/sys/fs/cgroup/cpu.max", "50000 100000\n", "/sys/fs/cgroup/cpu.stat", V2_STAT), 0.5),
              arguments("v1", files("/sys/fs/cgroup/cpu/cpu.cfs_quota_us", "200000\n",
                      "/sys/fs/cgroup/cpu/cpu.cfs_period_us", "100000\n", "/sys/fs/cgroup/cpu/cpu.stat", V1_STAT), 2.0),
              arguments("v1 under cpu,cpuacct", files("/sys/fs/cgroup/cpu,cpuacct/cpu.cfs_quota_us", "150000\n",
                      "/sys/fs/cgroup/cpu,cpuacct/cpu.cfs_period_us", "100000\n",
                      "/sys/fs/cgroup/cpu,cpuacct/cpu.stat", V1_STAT), 1.5));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void readsTheLimitAndCounters(String description, Map<String, String> files, double limitCpus) {
      FakeJvm jvm = new FakeJvm();
      jvm.files.putAll(files);

      CgroupCpu cpu = CgroupCpu.read(jvm);

      assertThat(cpu.limitCpus).isEqualTo(limitCpus);
      assertThat(cpu.periods).isEqualTo(42);
      assertThat(cpu.throttledPeriods).isEqualTo(21);
  }
  ```

  And `hasNoLimit(String description, Map<String, String> files)`, asserting
  `CgroupCpu.read(jvm)` is null, with these rows:

  | Description | Files |
  | --- | --- |
  | no cgroup files | none |
  | v2 without a quota | `cpu.max` = `max 100000\n`, `cpu.stat` = `V2_STAT` |
  | v2 without a quota, v1 files also present | the row above plus the three v1 files of the `v1` row |
  | v1 without a quota | quota `-1\n`, period `100000\n`, `cpu.stat` = `V1_STAT` |
  | v2 with a zero period | `cpu.max` = `100000 0\n` |
  | v2 with one field | `cpu.max` = `100000\n` |
  | v2 with text for a quota | `cpu.max` = `lots 100000\n` |
  | v2 without `cpu.stat` | `cpu.max` = `100000 100000\n` only |
  | v2 with a counter missing | `cpu.max` = `100000 100000\n`, `cpu.stat` = `nr_periods 42\n` |
  | v2 with text for a counter | `cpu.max` = `100000 100000\n`, `cpu.stat` = `nr_periods many\nnr_throttled 21\n` |

  `files(String... pathsAndTexts)` is a private helper that builds the map.

- [ ] **Step 4: Run `TestCgroupCpu`.** Expected: it does not compile, because
      `CgroupCpu` does not exist.

- [ ] **Step 5: Implement `CgroupCpu`.** A counter is the number on the
      `cpu.stat` line that starts with its name and a space. Every parse
      failure, including a `NumberFormatException`, a quota or period at or
      below zero, and a negative counter, gives null. Add `FakeJvm.cgroupV2`,
      which writes `cpu.max` as `QUOTA 100000\n` and a `cpu.stat` shaped like
      `V2_STAT`.

- [ ] **Step 6: Run `TestCgroupCpu`, then the module's tests on both JVMs.**
      Expected: pass.

- [ ] **Step 7: Commit.**
      `feat: read the container's cpu limit and throttling counters`

### Task 2: Hold the limit and the window's throttling in the snapshot

**Files:**

- Modify: `JvmSnapshot.java`, `FakeJvm.java`, `TestJvmSnapshot.java`

**Interfaces:**

- Consumes: `CgroupCpu.read(JvmSources)`, `FakeJvm.cgroupV2(long, long, long)`,
  `FakeJvm.files`.

- Produces, on `JvmSnapshot`:

  ```java
  /** The container's CPU limit in CPUs, or -1 when it has none. */
  final double cpuLimit;
  /** The periods the kernel has counted for the container, or 0 without a limit. */
  final long cpuPeriods;
  /** The periods the container was throttled in, or 0 without a limit. */
  final long cpuThrottledPeriods;
  /** The periods inside the sampling window, or 0 when not sampled. */
  final long windowCpuPeriods;
  /** The throttled periods inside the sampling window, or 0 when not sampled. */
  final long windowCpuThrottledPeriods;
  ```

- Produces, on `FakeJvm`:
  `Map<String, String> filesAfterPause = new HashMap<>()`, which `pause` copies
  into `files` before it returns or throws; an entry with a null value removes
  the file.

- [ ] **Step 1: Write the failing tests in `TestJvmSnapshot`.**

  ```java
  @Test
  void holdsTheCpuLimitAndTheThrottledPeriodsOfTheWindow() {
      FakeJvm jvm = new FakeJvm();
      jvm.cgroupV2(100_000, 100, 10);
      FakeJvm later = new FakeJvm();
      later.cgroupV2(100_000, 142, 31);
      jvm.filesAfterPause.putAll(later.files);

      JvmSnapshot snapshot = JvmSnapshot.read(jvm, Duration.ofSeconds(1));

      assertThat(snapshot.cpuLimit).isEqualTo(1.0);
      assertThat(snapshot.cpuPeriods).isEqualTo(142);
      assertThat(snapshot.cpuThrottledPeriods).isEqualTo(31);
      assertThat(snapshot.windowCpuPeriods).isEqualTo(42);
      assertThat(snapshot.windowCpuThrottledPeriods).isEqualTo(21);
  }
  ```

  And one parameterized test, `holdsNoWindowThrottling`, whose rows each assert
  the limit, the totals, and that both window counts are 0:

  | Row | Start | End | Window | Expected limit, periods, throttled |
  | --- | --- | --- | --- | --- |
  | not sampled | limit 1 CPU, 142, 31 | same | zero | 1.0, 142, 31 |
  | no limit | no files | no files | 1 s | -1, 0, 0 |
  | limit appears | no files | limit 1 CPU, 142, 31 | 1 s | 1.0, 142, 31 |
  | limit goes away | limit 1 CPU, 100, 10 | `cpu.max` removed | 1 s | -1, 0, 0 |
  | counters go down | limit 1 CPU, 100, 10 | limit 1 CPU, 5, 1 | 1 s | 1.0, 5, 1 |
  | interrupted in the window | limit 1 CPU, 100, 10 | limit 1 CPU, 142, 31, `interruptPauseAfter` 250 ms | 1 s | 1.0, 142, 31 |

  End the interrupted row with `Thread.interrupted()` so the flag does not leak
  into the next test.

- [ ] **Step 2: Run `TestJvmSnapshot`.** Expected: it does not compile, because
      the fields do not exist.

- [ ] **Step 3: Implement.** `Reading.read` calls `CgroupCpu.read(jvm)` into a
      nullable field. The snapshot's limit and totals come from the end reading.
      A window count is the end count minus the start count when the dump is
      sampled and both readings have a limit, and 0 when that difference is
      negative for either counter.

- [ ] **Step 4: Run `TestJvmSnapshot`, then the module's tests on both JVMs.**
      Expected: pass.

- [ ] **Step 5: Commit.**
      `feat: sample the container's throttled periods over the window`

### Task 3: Print the `CPU Limit` line

**Files:**

- Modify: `JvmInfoSection.java`, `TestJvmInfoSection.java`

**Interfaces:**

- Consumes: the five `JvmSnapshot` fields of Task 2, `FakeJvm.cgroupV2`,
  `FakeJvm.filesAfterPause`.
- Produces: `static final String CPU_LIMIT = "CPU Limit"` on `JvmInfoSection`,
  documented as the title of the line that `CpuThrottledCheck` marks.

The line follows `System Processors` and is absent when `snapshot.cpuLimit < 0`.
Its value, then `findings.marker(Finding.lineMark(CPU_LIMIT))`:

| Snapshot | Value |
| --- | --- |
| sampled | `1.00 CPUs; throttled in 21 of 42 periods; 1,203 of 86,400 since the container started` |
| not sampled | `1.00 CPUs; throttled in 1,203 of 86,400 periods since the container started` |

The limit prints with `%.2f` and each count with `%,d`.

- [ ] **Step 1: Write the failing test `printsTheCpuLimitAfterSystemProcessors` in
  `TestJvmInfoSection`.** It is parameterized over three rows and asserts
  `containsSubsequence(titled("System Processors", "4 amd64"), titled("CPU Limit", EXPECTED), titled("System Memory", ...))`
  on the rendered lines:

  | Row | Start | End | Window | Expected value |
  | --- | --- | --- | --- | --- |
  | sampled | 1 CPU, 86,358 and 1,182 | 1 CPU, 86,400 and 1,203 | 1 s | the sampled value above |
  | not sampled | 1 CPU, 86,400 and 1,203 | same | zero | the not-sampled value above |
  | half a CPU | quota 50,000, 86,400 and 1,203 | same | zero | `0.50 CPUs; throttled in 1,203 of 86,400 periods since the container started` |

  The existing `printsJvmAndHostInfo` uses `containsExactly` on a JVM without
  cgroup files, so it already proves the line is absent without a limit.

- [ ] **Step 2: Run `TestJvmInfoSection`.** Expected: the new test fails,
      because no `CPU Limit` line prints.

- [ ] **Step 3: Implement the line.**

- [ ] **Step 4: Run `TestJvmInfoSection`, then the module's tests on both
      JVMs.** Expected: pass.

- [ ] **Step 5: Commit.**
      `feat: print the container's cpu limit and throttled periods`

### Task 4: Flag frequent throttling

**Files:**

- Create: `CpuThrottledCheck.java`, `TestCpuThrottledCheck.java`
- Modify: `Checks.java`, `TestJvmThreadDump.java`, `README.md`

**Interfaces:**

- Consumes: the five `JvmSnapshot` fields, `snapshot.sampled()`,
  `snapshot.windowNanos`, `snapshot.uptimeMillis`, `JvmInfoSection.CPU_LIMIT`,
  `Finding.lineMark`.
- Produces:

  ```java
  final class CpuThrottledCheck implements Check
  {
      static final String TITLE = "CPU Throttled";
      static final int MIN_WINDOW_PERCENT = 25;
      static final long MIN_WINDOW_THROTTLED_PERIODS = 2;
      static final int MIN_LIFETIME_PERCENT = 10;
      static final long MIN_LIFETIME_UPTIME_MILLIS = 300_000;
  }
  ```

`applies` is true when `snapshot.cpuLimit >= 0`. The finding has one line per
measure that flagged, window first, then the limit line, and marks
`line:CPU Limit`:

| Measure | Flags when | Line |
| --- | --- | --- |
| Window | sampled, `windowCpuThrottledPeriods >= 2`, and `windowCpuThrottledPeriods * 100 >= 25 * windowCpuPeriods` | `the kernel throttled this container in 21 of 42 periods (50%) of the last 4.1s` |
| Lifetime | `uptimeMillis >= 300_000` and `cpuThrottledPeriods * 100 >= 10 * cpuPeriods` and `cpuPeriods > 0` | `the kernel has throttled this container in 12% of its periods, an average since it started` |
| always last | | `the limit is 1.00 CPUs` |

Percentages are whole numbers, rounded down. The window length prints as `%.1f`
seconds, as `GcOverheadCheck` prints it.

- [ ] **Step 1: Write the failing test `TestCpuThrottledCheck`.** Build each
  snapshot with `FakeJvm.cgroupV2`, `filesAfterPause`, and
  `when(jvm.runtime.getUptime())`. `FakeJvm`'s default uptime is 90,061,000 ms,
  so every window row below sets the lifetime counters under 10%.

  `flagsAThrottledWindow(long throttled, long periods, boolean flagged)`, with a
  one-second window and lifetime counters of 0 of 1,000 at the start:

  | Throttled in window | Periods in window | Flagged |
  | --- | --- | --- |
  | 3 | 10 | yes |
  | 2 | 10 | no |
  | 2 | 8 | yes |
  | 2 | 2 | yes |
  | 1 | 1 | no |
  | 0 | 10 | no |

  `flagsALifetimeOfThrottling(long throttled, long periods, long uptimeMillis, boolean flagged)`,
  with a zero window:

  | Throttled | Periods | Uptime (ms) | Flagged |
  | --- | --- | --- | --- |
  | 8,640 | 86,400 | 300,000 | yes |
  | 8,639 | 86,400 | 300,000 | no |
  | 8,640 | 86,400 | 299,999 | no |
  | 0 | 0 | 300,000 | no |

  ```java
  @Test
  void printsEachMeasureThatFlaggedThenTheLimit() {
      FakeJvm jvm = new FakeJvm();
      jvm.cgroupV2(100_000, 86_358, 10_344);
      FakeJvm later = new FakeJvm();
      later.cgroupV2(100_000, 86_400, 10_365);
      jvm.filesAfterPause.putAll(later.files);

      List<Finding> findings = new CpuThrottledCheck().findings(jvm.snapshot(Duration.ofMillis(4100)));

      assertThat(findings).hasSize(1);
      assertThat(findings.get(0).title).isEqualTo("CPU Throttled");
      assertThat(findings.get(0).lines).containsExactly(
              "the kernel throttled this container in 21 of 42 periods (50%) of the last 4.1s",
              "the kernel has throttled this container in 11% of its periods, an average since it started",
              "the limit is 1.00 CPUs");
      assertThat(findings.get(0).marks).containsExactly("line:CPU Limit");
  }

  @Test
  void appliesOnlyWithACpuLimit() {
      FakeJvm limited = new FakeJvm();
      limited.cgroupV2(100_000, 100, 0);

      assertThat(new CpuThrottledCheck().applies(limited.snapshot())).isTrue();
      assertThat(new CpuThrottledCheck().applies(new FakeJvm().snapshot())).isFalse();
  }
  ```

- [ ] **Step 2: Run `TestCpuThrottledCheck`.** Expected: it does not compile,
      because `CpuThrottledCheck` does not exist.

- [ ] **Step 3: Implement `CpuThrottledCheck`.**

- [ ] **Step 4: Run `TestCpuThrottledCheck`.** Expected: pass.

- [ ] **Step 5: Write the failing dump test in `TestJvmThreadDump`.**

  ```java
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
              .contains(titled("CPU Throttled", "the kernel throttled this container in 5 of 10 periods (50%) of the last 1.0s"))
              .contains(titled("CPU Limit", "1.00 CPUs; throttled in 5 of 10 periods; 5 of 110 since the container started <<< CPU Throttled"));
      assertThat(dump.indexOf("CPU Saturated")).isLessThan(0);
  }
  ```

  The existing `countsNineChecksWithAWindow` stays as it is and proves a JVM
  without a limit still counts nine.

- [ ] **Step 6: Run `TestJvmThreadDump`.** Expected: the new test fails with 9
      checks.

- [ ] **Step 7: Add `new CpuThrottledCheck()` to `Checks.all` after
      `new CpuSaturatedCheck()`.**

- [ ] **Step 8: Run the module's tests on both JVMs.** Expected: pass.

- [ ] **Step 9: Update `README.md`.**
  - "Why add it": "Nine checks" becomes "Ten checks".
  - "Reading the findings": add `CPU Throttled` to the checks that skip
    themselves, "when the container has no CPU limit or the JVM does not run in
    one", and say the count is then nine.
  - "The checks" table: a row after `CPU Saturated`:
    `| CPU Throttled | uses one | The kernel throttled the container in 25% or more of the window's periods and at least 2 of them, or in 10% or more of all its periods after the first 5 minutes. Needs a container with a CPU limit. |`
  - The bullet list under the table: a `**CPU Throttled**` bullet that says what
    throttling is, that a load under 0.90 can hide it, that the counts come from
    the container's cgroup v1 or v2 files at `/sys/fs/cgroup`, and that a JVM in
    a nested cgroup on a plain host, such as a systemd service with `CPUQuota`,
    gets no line and no check.
  - "The sampling window": after the paragraph on cgroup versions, one sentence
    that `CPU Limit` and `CPU Throttled` read the cgroup files themselves and so
    work on any JVM.
  - Glossary: `CPU Limit`: the container's CPU quota divided by its period, then
    the periods the kernel throttled it in, during the window and since the
    container started. Absent without a limit.
  - Example Output: leave as it is; it shows a JVM outside a container.

- [ ] **Step 10: Commit.**
      `feat: flag a container that the kernel throttles often`
