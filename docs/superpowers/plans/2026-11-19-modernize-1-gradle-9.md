# Gradle 9 Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build jvm-threaddump with Gradle 9.9.2, Kotlin build scripts, and a
Java 8 toolchain, publish with plain `maven-publish`, and make the existing
tests pass reliably on JUnit 5.

**Architecture:** A convention plugin in an included build, `build-logic`, holds
the setup both modules share. Each module's `build.gradle.kts` applies it and
declares only its own dependencies. Versions live in
`gradle/libs.versions.toml`, and the project version lives in
`gradle.properties`.

**Tech Stack:** Gradle 9.9.2 (Kotlin DSL), Java 8 toolchain with Gradle run on
Java 21 via mise, foojay toolchain resolver 1.0.0, JUnit Jupiter 5.14.4,
AssertJ 3.27.7.

**Spec:**
`docs/superpowers/specs/2026-11-19-modernize-build-and-tests-design.md` (layer
1, plus bug fix 7)

**Issue:** https://github.com/Widen/jvm-threaddump/issues/1

## Global Constraints

- Gradle wrapper `9.9.2`; Gradle itself runs on Java 21 (`mise.toml`:
  `java = "21"`).
- Compile and test with a Java 8 toolchain: `JavaLanguageVersion.of(8)`.
  Output class files are major version 52.
- Coordinates: group `widen`, artifacts `jvm-threaddump-generator` and
  `jvm-threaddump-servlet`, version `3.0.0-SNAPSHOT` from `gradle.properties`.
- Dependencies resolve from
  `https://jfrog.ais.acquia.io/artifactory/widen-java-virtual/` with
  `ACQUIA_JFROG_USER` and `ACQUIA_JFROG_TOKEN` from the environment, falling
  back to Gradle properties of the same names.
- Publish to `https://jfrog.ais.acquia.io/artifactory/libs-snapshots-local`
  when the version ends in `-SNAPSHOT`, otherwise to
  `https://jfrog.ais.acquia.io/artifactory/libs-releases-local`.
- Test libraries must run on Java 8: JUnit Jupiter 5.x (not 6), AssertJ 3.x.
- No nebula plugins, no `idea` plugin, no Groovy build scripts, no Spotless.
- The main Java sources under `generator/src/main` and `servlet/src/main` do
  not change in this plan.

## Review Focus

1. **Missing JFrog credentials:** a developer without `ACQUIA_JFROG_USER` or
   `ACQUIA_JFROG_TOKEN` should get an error that names the missing variable,
   not "Cannot query the value of this provider". Task 1 Step 7 checks it.
2. **Servlet API leaking to consumers:** the published servlet POM must not
   list `javax.servlet-api`, because it is `compileOnly`. Task 3 Step 3 checks
   it.
3. **Bytecode level:** a class file compiled at major version 53 or later would
   fail to load on Java 8. Task 1 Step 6 checks it.
4. **Deadlock left behind:** after the deadlock test, a fresh dump in the same
   JVM must report "Deadlocked Threads: None". Task 2's test asserts it.
5. **Snapshot or release repository:** `-Pversion=3.0.0` must choose the
   releases repository, and the default `-SNAPSHOT` version the snapshots
   repository. Task 3 Step 4 checks it.

---

### Task 1: Gradle 9 build with Kotlin scripts

**Files:**

- Create: `mise.toml`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `settings.gradle.kts`
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/jvm-threaddump.java-library.gradle.kts`
- Create: `generator/build.gradle.kts`
- Create: `servlet/build.gradle.kts`
- Modify: `gradle/wrapper/gradle-wrapper.properties`,
  `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` (regenerated)
- Modify: `generator/src/test/java/com/widen/util/td/TestCustom.java`
- Modify: `generator/src/test/java/com/widen/util/td/TestDump.java`
- Modify: `generator/src/test/java/com/widen/util/td/TestDeadlock.java`
- Delete: `build.gradle`, `settings.gradle`, `servlet/build.gradle`

**Interfaces:**

- Produces: the convention plugin ID `jvm-threaddump.java-library`, which
  Task 3 extends with publishing. The catalog aliases `libs.junit.bom`,
  `libs.junit.jupiter`, `libs.junit.launcher`, `libs.assertj.core`,
  and `libs.servlet.api`.

- [ ] **Step 1: Write `mise.toml`, `gradle.properties`, and the catalog**

`mise.toml` has `[tools]` with `java = "21"`. `gradle.properties` has
`version=3.0.0-SNAPSHOT` and `group=widen`. `gradle/libs.versions.toml`:

```toml
[versions]
junit = "5.14.4"
assertj = "3.27.7"

[libraries]
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
servlet-api = { module = "javax.servlet:javax.servlet-api", version = "3.0.1" }
```

- [ ] **Step 2: Write the settings and the `build-logic` build**

`settings.gradle.kts`: `pluginManagement { includeBuild("build-logic") }`;
apply `org.gradle.toolchains.foojay-resolver-convention` version `1.0.0`;
`rootProject.name = "jvm-threaddump"`; include `generator` and `servlet` and
rename them to `jvm-threaddump-generator` and `jvm-threaddump-servlet`, as the
Groovy `settings.gradle` does.

`build-logic/settings.gradle.kts` resolves from `gradlePluginPortal()` and
imports the root catalog with `from(files("../gradle/libs.versions.toml"))`.
`build-logic/build.gradle.kts` applies `` `kotlin-dsl` `` and nothing else.

- [ ] **Step 3: Write the convention plugin `jvm-threaddump.java-library`**

It applies `java-library` and sets:

- the Java 8 toolchain;
- the JFrog `widen-java-virtual` Maven repository with basic authentication;
- `tasks.test { useJUnitPlatform() }`;
- `testImplementation(platform(junit-bom))`,
  `testImplementation(junit-jupiter)`, `testImplementation(assertj-core)`, and
  `testRuntimeOnly(junit-launcher)`, looked up from the `libs` catalog
  through `VersionCatalogsExtension`, since precompiled script plugins get no
  type-safe `libs` accessor.

Read each credential through one helper,
`fun jfrogCredential(name: String): String`, that returns the environment
variable, else the Gradle property, else throws
a `GradleException` with the message
`Set $name in the environment or in ~/.gradle/gradle.properties`.

- [ ] **Step 4: Write the module build files and delete the Groovy scripts**

`generator/build.gradle.kts` applies the convention plugin only.
`servlet/build.gradle.kts` applies it and declares
`api(project(":jvm-threaddump-generator"))` and `compileOnly(libs.servlet.api)`.
Delete `build.gradle`, `settings.gradle`, and `servlet/build.gradle`.

- [ ] **Step 5: Regenerate the wrapper and port the tests to JUnit 5**

Set `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` to
`https\://services.gradle.org/distributions/gradle-9.9.2-bin.zip`, then run
`mise exec -- ./gradlew wrapper --gradle-version 9.9.2` to regenerate the jar
and scripts.

In `TestCustom`, `TestDump`, and `TestDeadlock`, replace `org.junit.Test` with
`org.junit.jupiter.api.Test` and each Hamcrest
`assertThat(dump, containsString(x))` with AssertJ
`assertThat(dump).contains(x)` (`doesNotContain` for the `not(...)` case). Keep
every assertion. `TestDeadlock` stays racy until Task 2.

- [ ] **Step 6: Verify the build compiles to Java 8 and one test passes**

Run:

```bash
mise exec -- ./gradlew assemble testClasses -q --console=plain 2>&1 | tail -40
```

Expected: no output and no `BUILD FAILED`.

Run:

```bash
mise exec -- ./gradlew :jvm-threaddump-generator:test \
  --tests com.widen.util.td.TestCustom --console=plain 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. If Gradle reports that Java 8 is unsupported for
toolchains, stop and ask before changing the approach.

Run:

```bash
javap -v -cp generator/build/classes/java/main \
  com.widen.util.td.JvmThreadDumpImpl | rg 'major version'
```

Expected: `major version: 52`.

- [ ] **Step 7: Verify the missing-credential error**

Run:

```bash
env -u ACQUIA_JFROG_USER mise exec -- ./gradlew help -q --console=plain 2>&1 \
  | rg ACQUIA_JFROG_USER
```

Expected: the line
`Set ACQUIA_JFROG_USER in the environment or in ~/.gradle/gradle.properties`,
unless `~/.gradle/gradle.properties` defines it; then expect no error.

- [ ] **Step 8: Commit**

```bash
git add -A mise.toml gradle.properties gradle settings.gradle.kts build-logic \
  generator/build.gradle.kts servlet/build.gradle.kts gradlew gradlew.bat \
  generator/src/test build.gradle settings.gradle servlet/build.gradle
git commit -m "build: move to gradle 9.9.2 with kotlin scripts and java 8"
```

### Task 2: Deadlock test that cleans up after itself

**Files:**

- Create: `generator/src/test/java/com/widen/util/td/DeadlockFixture.java`
- Modify: `generator/src/test/java/com/widen/util/td/TestDeadlock.java`
- Delete: `generator/src/test/java/com/widen/util/td/DeadlockThread.java`

**Interfaces:**

- Produces: `final class DeadlockFixture implements AutoCloseable` with
  `static DeadlockFixture start()`, `long[] threadIds()`, and `void close()`.
  Layer 2's real-JVM deadlock test uses it.

- [ ] **Step 1: Write the failing test**

Replace the body of `TestDeadlock` with:

```java
@Test
void reportsDeadlockedThreadsAndClearsThemAfterward() throws Exception {
    try (DeadlockFixture deadlock = DeadlockFixture.start()) {
        String dump = new JvmThreadDumpImpl().generate();
        assertThat(dump).doesNotContain("Deadlocked Threads: None");
        for (long id : deadlock.threadIds()) {
            assertThat(dump).contains("0x" + Long.toHexString(id));
        }
    }
    assertThat(new JvmThreadDumpImpl().generate())
            .contains("Deadlocked Threads: None");
}
```

- [ ] **Step 2: Run it to verify it fails**

Run:

```bash
mise exec -- ./gradlew :jvm-threaddump-generator:test \
  --tests com.widen.util.td.TestDeadlock --console=plain 2>&1 | tail -20
```

Expected: compilation fails because `DeadlockFixture` does not exist.

- [ ] **Step 3: Implement `DeadlockFixture` and delete `DeadlockThread`**

Two daemon threads share two private `ReentrantLock`s and a
`CountDownLatch(2)`. Each thread runs:

```java
first.lockInterruptibly();
try {
    bothHoldFirstLock.countDown();
    bothHoldFirstLock.await();
    second.lockInterruptibly();   // blocks forever: the other thread holds it
    second.unlock();
} catch (InterruptedException e) {
    // close() interrupted us; fall through and release
} finally {
    first.unlock();
}
```

Thread one takes lock A then B; thread two takes B then A. `start()` returns
once `ManagementFactory.getThreadMXBean().findDeadlockedThreads()` contains
both thread ids, polling every 10 ms, and throws `IllegalStateException` after
5 seconds. `close()` interrupts both threads and joins each with a 5-second
timeout.

- [ ] **Step 4: Run the full build to verify it passes**

Run:

```bash
mise exec -- ./gradlew build --rerun-tasks --console=plain 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, with `TestDump`'s "Deadlocked Threads: None"
assertion passing whichever class runs first. Run it three times; all three
pass.

- [ ] **Step 5: Commit**

```bash
git add -A generator/src/test
git commit -m "test: end the deadlock fixture's deadlock before other tests run"
```

### Task 3: Publishing with `maven-publish`

**Files:**

- Modify: `build-logic/src/main/kotlin/jvm-threaddump.java-library.gradle.kts`

**Interfaces:**

- Consumes: the convention plugin and `jfrogCredential(name)` from Task 1.
- Produces: a `mavenJava` publication in each module and a repository named
  `jfrog`, so the publish task is
  `publishMavenJavaPublicationToJfrogRepository`.
- [ ] **Step 1: Add the publication and repository**

Apply `maven-publish`, add `java { withSourcesJar() }`, and create a `mavenJava`
`MavenPublication` from `components["java"]`. The artifact ID is the project
name. Add a `jfrog` Maven repository whose URL is chosen from
`project.version.toString().endsWith("-SNAPSHOT")` as Global Constraints
states, with basic authentication from `jfrogCredential`.

- [ ] **Step 2: Publish locally**

Run:

```bash
mise exec -- ./gradlew publishToMavenLocal -q --console=plain 2>&1 | tail -20
```

Expected: no `BUILD FAILED`.
`~/.m2/repository/widen/jvm-threaddump-generator/3.0.0-SNAPSHOT/` and
`~/.m2/repository/widen/jvm-threaddump-servlet/3.0.0-SNAPSHOT/` each hold a JAR,
a `-sources.jar`, and a `.pom`.

- [ ] **Step 3: Check the POMs**

Run:

```bash
rg -c '<dependency>' \
  ~/.m2/repository/widen/jvm-threaddump-generator/3.0.0-SNAPSHOT/*.pom
```

Expected: no match (the generator has no dependencies).

Run:

```bash
rg -o '<artifactId>[^<]+' \
  ~/.m2/repository/widen/jvm-threaddump-servlet/3.0.0-SNAPSHOT/*.pom
```

Expected: `jvm-threaddump-servlet` and `jvm-threaddump-generator` only; no
`javax.servlet-api`.

- [ ] **Step 4: Check the repository choice**

Add no task for this. Run a throwaway init script from the scratchpad that
prints each `jfrog` repository URL after evaluation:

```kotlin
allprojects {
    afterEvaluate {
        extensions.findByType<PublishingExtension>()?.repositories?.forEach {
            println("$name ${(it as MavenArtifactRepository).url}")
        }
    }
}
```

Run:

```bash
mise exec -- ./gradlew -I SCRATCH/print-repos.init.gradle.kts help -q
```

Expected: both modules print `.../libs-snapshots-local`.

Run the same command with `-Pversion=3.0.0`.
Expected: both modules print `.../libs-releases-local`.

- [ ] **Step 5: Commit**

```bash
git add build-logic
git commit -m "build: publish with maven-publish instead of nebula"
```

### Task 4: README build instructions

**Files:**

- Modify: `readme.md`

- [ ] **Step 1: Add a Build section**

Add a "Build" section after the existing usage text. It says to run
`mise install`, then `./gradlew test`; that Gradle downloads a Java 8 JDK
when none is installed; that the version lives in `gradle.properties`; and
that `./gradlew publish` sends a `-SNAPSHOT` version to `libs-snapshots-local`
and any other version to `libs-releases-local`, using `ACQUIA_JFROG_USER` and
`ACQUIA_JFROG_TOKEN`. Leave the usage examples alone; layer 2 fixes them.

- [ ] **Step 2: Format and lint it**

Run:

```bash
mise exec rumdl -- rumdl fmt --flavor gfm readme.md
mise exec rumdl -- rumdl check --flavor gfm readme.md
```

Expected: `No issues found`, except findings in lines this task did not touch,
which stay as they are.

- [ ] **Step 3: Commit**

```bash
git add readme.md
git commit -m "docs(readme): describe the gradle 9 build and publishing"
```
