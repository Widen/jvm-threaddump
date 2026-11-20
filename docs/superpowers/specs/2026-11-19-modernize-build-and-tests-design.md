# Modernize the build and add unit tests

- **Date:** 2026-11-19
- **Repo:** `Widen/jvm-threaddump`
- **Stack:** `modernize-1-gradle-9` then `modernize-2-testable-refactor`, based
  on `master`

## Intent

jvm-threaddump has not changed in years. It builds with Gradle 5.1.1 and
Groovy scripts that use the `compile` and `testCompile` configurations, which
Gradle 7 removed, and nebula plugins that do not run on current Gradle. Its
four tests mostly print a dump to stdout. A code review found about ten bugs,
including threads that disappear from the dump and secrets printed from `-D`
arguments.

Before fixing those bugs one by one, the project needs a build that runs on
current Gradle and tests that pin what the dump prints. This work delivers
both, and fixes the review's bugs where the refactor touches them.

## Goals

- Build with Gradle 9.9.2 and Kotlin build scripts (`*.gradle.kts`).
- Build on a Java 17 toolchain, compile with `--release 8` so the library runs
  on Java 8 and later, and run the tests on both Java 17 and Java 8.
- Publish both artifacts to Acquia JFrog with plain `maven-publish`.
- Refactor the generator so every section of the dump can be unit tested with
  fake JVM data.
- Replace the subclass hooks with a builder, and let `ThreadDumpServlet` serve
  a configured dump, so consumers neither subclass the generator nor copy the
  servlet.
- Cover most current behavior with unit tests: every section of the dump, the
  thread ordering, the formatting helpers, and the servlet's response.
- Fix the review's bugs listed under Bug fixes.

## Non-goals

- Calling Java 9 or later APIs. `--release 8` rejects them. A multi-release
  JAR can add them when a need appears, such as listing virtual threads on
  Java 21.
- A Buildkite pipeline. A later change adds one.
- Changing the dump's layout beyond what a bug fix needs. Section order, titles,
  and the 22-column title padding stay.
- Running Spotless or adding a formatter.

## Anti-goals

- Leaving mica without an upgrade path. `Widen/mica` is the only known
  consumer, and its `java17-alpha` line, which builds on Java 17, takes this
  release. `mica-web-app/.../ThreadDumpServletImpl.java` depends on
  `widen:jvm-threaddump-generator:2.0.0`, subclasses `JvmThreadDumpImpl` to
  supply main arguments and custom values, and copies the servlet's response
  code. Version 3.0.0 changes the public API, so mica changes when it
  upgrades. A test here copies the servlet mica will write, so the new API
  covers everything mica does today.

## Constraints

- **Public API:** `JvmThreadDump` is a public final class with
  `static JvmThreadDump create()`, `static Builder builder()`, and
  `String generate()`. `JvmThreadDump.Builder` has
  `mainArguments(List<String>)`, `customValues(Map<String, ?>)`, and
  `build()`. `JvmThreadDumpImpl` and its protected hooks are removed.
  `ThreadDumpServlet` keeps its name, package, and no-arg constructor, and
  gains a public constructor that takes a `JvmThreadDump`. Every other class
  is package-private and free to change, including `Line` and
  `ThreadDumpProducer`, which are public in 2.0.0.
- **Coordinates:** group `widen`, artifacts `jvm-threaddump-generator` and
  `jvm-threaddump-servlet`. The Gradle project names stay the same.
- **Repositories:** credentials come from the `ACQUIA_JFROG_USER` and
  `ACQUIA_JFROG_TOKEN` environment variables, falling back to Gradle
  properties of the same names. When both are set, dependencies resolve from
  `https://jfrog.ais.acquia.io/artifactory/widen-java-virtual/`; otherwise
  they resolve from Maven Central, so a build without credentials still runs.
  Publishing requires the credentials and fails with an error naming the
  missing ones.
- **Test libraries:** tests also run on Java 8, and JUnit 6 needs Java 17 and
  Mockito 5 needs Java 11, so tests use JUnit Jupiter 5.14.4, AssertJ 3.27.7,
  and Mockito 4.11.0. Test code compiles with `--release 8` too.
- **No Java 9+ calls:** `--release 8` hides `ThreadInfo.getPriority()` and
  `isDaemon()`, which Java 9 added, so priority, daemon status, thread group,
  and `toString()` still come from `Thread` objects.

## Approaches

### Refactor

1. **Inject the JVM data (chosen).** A package-private constructor takes one
   holder with everything the dump reads. Each section of the dump becomes a
   small class that returns its lines. Tests pass in Mockito fakes, so the
   output is deterministic and rare cases can be forced. This approach has the
   widest coverage and the largest diff.
2. **Test the formatting functions only.** Pull the formatting into static
   helpers and check `generate()` against the real JVM by looking for expected
   text. The diff is smaller, but section assembly and rare cases, such as a
   null pool usage, a deadlock, or two threads with colliding sort keys, stay
   untested.

Approach 1 was chosen because the point of the work is a safety net for the bug
fixes, and approach 2 leaves the bugs it would catch untested.

### Versioning

Removing nebula removes the version it derived from git tags.

1. **`version` in `gradle.properties` (chosen).** The file holds
   `version=3.0.0-SNAPSHOT` and is edited for each release. A version ending in
   `-SNAPSHOT` publishes to `libs-snapshots-local`; any other version publishes
   to `libs-releases-local`. It is simple and readable; the git tag and the
   version can drift unless the release step checks them.
2. **Version from `git describe`.** This keeps tag-driven versions, but adds git
   logic to the build and breaks in a shallow CI clone.
3. **`-Pversion` from CI.** This keeps the build simplest, but a local release
   requires typing the version.

### Java toolchain

Gradle 9 runs only on Java 17 or later. The build compiles and tests with a
Java 17 toolchain and compiles all code with `options.release = 8`, so the
JARs load on Java 8 and the compiler rejects any Java 9+ API. `test` runs the
tests on Java 17, the runtime of mica's `java17-alpha` line, the consumer of
this release. `testOnJava8` runs the same test classes on a Java 8 launcher,
so the shipped bytecode is exercised on Java 8's real memory pools and
runtime. `check` depends on both.

Gradle finds the Java 8 JDK through `org.gradle.java.installations.fromEnv=JDK8`
in `gradle.properties`; `mise.toml` installs `corretto-8` next to Java 21 and
sets `JDK8` to its home. Without that variable, the foojay resolver downloads
a Java 8 JDK.

Java 9+ APIs stay out for now. The ones that would change the dump,
`ThreadInfo.getPriority()` and `isDaemon()`, still leave thread group and
`toString()` on `Thread`, so they would not remove the join in bug fix 9. A
multi-release JAR, with a `java9` source set under `META-INF/versions/9`, can
add them later.

## Layer 1: `modernize-1-gradle-9`

The build changes; the main Java code does not. The four existing tests move
to JUnit 5 and AssertJ with the same assertions, and bug fix 7 (the deadlock
test) moves into this layer, because the build cannot pass reliably without
it.

- **Wrapper:** Gradle 9.9.2, generated with `./gradlew wrapper`.
- **mise:** a new `mise.toml` sets `java = ["21", "corretto-8"]`, where the
  first entry runs Gradle, and sets
  `JDK8 = "{{env.HOME}}/.local/share/mise/installs/java/corretto-8"`. No other
  tools.
- **Toolchain:**
  `java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }`, and
  every `JavaCompile` task sets `options.release = 8`. A `testOnJava8` task
  runs the test classes with a Java 8 launcher, and `check` depends on it.
  `settings.gradle.kts` applies
  `org.gradle.toolchains.foojay-resolver-convention` so Gradle downloads a
  missing JDK.
- **Settings:** `settings.gradle.kts` includes `generator` and `servlet` and
  renames them to `jvm-threaddump-generator` and `jvm-threaddump-servlet`, as
  `settings.gradle` does today.
- **Shared configuration:** a `subprojects {}` block in the root
  `build.gradle.kts` holds the shared setup: the `java-library` plugin, the
  toolchain, the dependency repository, `maven-publish` with a sources JAR
  (`java { withSourcesJar() }`), the publish repository choice, and the JUnit
  Platform for `test`. Module build files declare only their own
  dependencies; a module with none has no build file.
- **Version catalog:** `gradle/libs.versions.toml` holds every dependency and
  plugin version.
- **Dependencies:** the servlet module uses
  `api(project(":jvm-threaddump-generator"))` and `compileOnly` for
  `javax.servlet:javax.servlet-api:3.0.1`, because the servlet container
  supplies it. Tests in the servlet module add the servlet API to
  `testImplementation`.
- **Removed:** the nebula plugins, the `idea` plugin and its `tasks.idea`
  wiring, `build.gradle`, `settings.gradle`, `servlet/build.gradle`, JUnit 4,
  and Hamcrest.
- **Version:** `gradle.properties` holds `version=3.0.0-SNAPSHOT`.
- **README:** a short Build section: `mise install`, then `./gradlew test`, and
  how to publish.

Done when `mise exec -- ./gradlew build` passes with the tests run on Java 17
and Java 8, the class files are major version 52 (Java 8), `publishToMavenLocal`
writes both artifacts with sources JARs and POMs to `~/.m2`, and the generator
POM lists no dependencies while the servlet POM lists the generator.

## Layer 2: `modernize-2-testable-refactor`

### Structure

- **`JvmSources`** (package-private): a holder for everything the dump reads
  from the JVM and its host. It holds the `RuntimeMXBean`, `MemoryMXBean`,
  `ThreadMXBean`, the memory pool and GC bean lists, the
  `java.lang.management.OperatingSystemMXBean`, a `java.time.Clock`, the
  default `TimeZone`, an environment lookup (`Function<String, String>`), a
  system property lookup, the `Runtime` memory figures, a reader for
  `/proc/uptime` that returns empty when the file is missing, and a supplier of
  `Thread.getAllStackTraces()`. A static `platform()` factory builds it from
  the real JVM.
- **`JvmThreadDump`:** `create()` and `builder().build()` read from
  `JvmSources.platform()`; a package-private builder method swaps in another
  `JvmSources`. `build()` copies the main arguments and custom values into
  unmodifiable collections, keeping the map's iteration order, so later
  changes to the caller's list or map do not reach the dump. On each call,
  `generate()` builds a local list of lines and calls `String.valueOf` on each
  custom value, so the instance is immutable and safe to share across threads.
- **Sections:** one package-private class per section: JVM info, memory, GC,
  deadlocks, and threads. Each takes a `JvmSources` and returns its lines.
  JVM info also takes the main arguments and custom values from the builder.
- **`ThreadDumpServlet`:** holds one `JvmThreadDump`, the one passed to its
  constructor or `JvmThreadDump.create()` for the no-arg constructor, and
  serves it on `GET`. A consumer that needs custom values subclasses the
  servlet and passes a built dump to `super`.
- **Helpers:** `DurationFormatUtils` and `StringUtils` shrink to a small
  duration formatter that handles the formats the dump uses. `BytesUtils` and
  `Line` stay, with the fixes below.

### Bug fixes

The numbers match the code review.

1. **Thread ordering:** sort threads by name ignoring case, then by name, then
   by id, so no two threads compare as equal.
2. **Secrets:** in "JVM System Props", replace the value of any `-D` property
   whose key contains `password`, `secret`, `token`, `credential`, or `key`,
   ignoring case, with `****`. Also mask a value that contains one of those
   words followed by `=`, ignoring case, such as a JDBC URL with
   `?password=x`. The key still prints.
3. **Servlet response:** set `text/plain; charset=UTF-8`, write the UTF-8 bytes
   of the dump to the output stream, and set the content length to their count.
4. **Instance state:** fixed by the immutable `JvmThreadDump` and the local
   list in `generate()`.
5. **Shared `DecimalFormat`:** create a `NumberFormat` per call.
6. **Null pool usage:** skip a pool whose `getUsage()` returns null.
7. **Deadlock test (done in layer 1):** a monitor deadlock lasts for the
   life of the test JVM, so every later test that expects
   "Deadlocked Threads: None" fails, and without the commented-out sleep the
   deadlock often never forms. Replace `DeadlockThread` with a test helper
   whose two daemon threads take private `ReentrantLock`s with
   `lockInterruptibly()`, using a latch so each holds its first lock before
   reaching for the second. `findDeadlockedThreads()` reports deadlocks on
   these locks. The test interrupts both threads and joins them in a `finally`
   block, so the deadlock ends before the next test runs. Deadlock formatting
   is mainly tested with a fake `ThreadMXBean` in layer 2.
8. **Pool order:** add the Java 8 G1 pool names (`G1 Eden Space`,
   `G1 Survivor Space`, `G1 Old Gen`) and Serial pool names (`Eden Space`,
   `Survivor Space`, `Tenured Gen`) and the Java 9+ code heap names
   (`CodeHeap 'non-nmethods'`, `CodeHeap 'profiled nmethods'`,
   `CodeHeap 'non-profiled nmethods'`) to the print order, drop the perm gen
   and short G1 names that no supported JVM reports, and print unlisted pools
   in name order.
9. **Consistent thread snapshot:** take state, lock, and stack from one
   `threadBean.dumpAllThreads(true, true)` call, joined by thread id to the
   `Thread` objects for priority, daemon status, group, and `toString()`. Print
   the lock a thread waits on in every waiting state, not only when it has an
   owner. Threads that end between the two calls are left out.
10. **README:** rewrite the examples for the builder and the servlet
    constructor, so they compile against the real API.

The review's smaller findings are fixed as follows:

- **OS bean cast:** read the `com.sun.management` extras only when the bean is
  an instance of that interface; otherwise print "Not Available".
- **GC time:** format collection time with milliseconds (`H:mm:ss.SSS`), so a
  total under one second is visible.
- **Memory header:** label the first column "used/committed, used/max".
- **`ThreadDumpServlet.init`:** remove the override, which only calls `super`.

Left as they are: the hostname still comes from `$HOSTNAME`, because a DNS
lookup from a diagnostic endpoint can hang on the host it is diagnosing.

### Tests

Each behavior gets a test; variations of one behavior share a parametrized
test.

- **Sections with fakes:** each section's exact lines for a fixed `JvmSources`:
  JVM info with and without custom values and with "Not Available" values;
  memory with ordered, unlisted, and null pools; GC; deadlocks with none and
  with two ids; threads with headers, lock info, and frames.
- **Thread ordering:** the review's colliding cases (`worker-1` id 23 and
  `worker-12` id 3; `Worker` id 12 and `worker1` id 2) all print.
- **Secret masking:** masked and unmasked keys.
- **Helpers:** byte sizes at unit boundaries, durations including sub-second GC
  time and multi-day uptime, and each `Line` type's padding.
- **Builder:** main arguments print in list order and custom values in the
  map's order, and changing the caller's list or map after `build()` changes
  nothing.
- **Repeat calls:** two `generate()` calls on one instance with fixed fakes
  return the same dump, not a growing one.
- **Real JVM:** one smoke test that `JvmThreadDump.create().generate()` prints
  every section title and "Deadlocked Threads: None", and one that the deadlock
  helper's threads show up as deadlocked.
- **Servlet:** with a Mockito `HttpServletResponse`, the content type, every
  header, and a content length that matches the UTF-8 byte count of a dump with
  non-ASCII text. A copy of the servlet mica will write (a `ThreadDumpServlet`
  subclass that passes a built dump with main arguments and custom values to
  `super`) serves those values.

Done when the module tests pass on Java 17 and on Java 8, the class files are
still major version 52, and every bug fix above has a test that fails without
it.

## Calls made without review

These were the designer's calls, not decisions made together:

- The servlet API becomes `compileOnly` in the servlet module.
- A version catalog holds the versions.
- The foojay resolver downloads a missing JDK.
- Secret masking matches `password`, `secret`, `token`, `credential`, and
  `key` in property names, and the same words followed by `=` in values.
- GC time gains milliseconds, and the memory header gets a new label.
- The hostname lookup stays as it is.
- The deadlock test fix moves into layer 1.

## Left to the builder

- Names of the section classes and of `JvmSources`' accessors.
- The duration formatter's shape, as long as it handles the formats above.
- How tests build fake `ThreadInfo` objects, which have no public
  constructor: Mockito mocks or `ThreadInfo.from(CompositeData)`.
