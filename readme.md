# JVM Thread Dump

When a Java service slows down, hangs, or runs out of memory in production, the
first question is what its threads are doing right now. Answering it usually
means a shell on the host, a JDK with `jstack` or `jcmd`, and permission to
attach to the process. A container image built on a JRE has none of those, and
an incident is a bad time to find out.

This library answers the question from inside the application. Add one servlet,
and `GET /td` returns a plain-text page that opens with a diagnosis: it names
the deadlock, the lock forty threads wait on, the heap that is full, or the
thread that uses half a core or more, and it marks the raw lines that prove it.
Below the diagnosis, the page lists everything else you would otherwise collect
by hand: every thread and its stack, memory pools, garbage collection, CPU load,
file descriptors, and the JVM's configuration.

```text
              Findings: 3 of 9 checks flagged something (sampled over 1.0s)

              Deadlock: 2 threads block each other and will never proceed
                        "worker-3" (0x1f) waits for Lock@5e2a, held by "worker-7"
                            com.widen.orders.Ledger.post(Ledger.java:88)
                        "worker-7" (0x2a) waits for Lock@1b9c, held by "worker-3"
                            com.widen.orders.Inventory.reserve(Inventory.java:41)
                        12 other threads wait behind them

          Lock Pile-up: 43 threads wait on a lock held by "qtp-exec-12" (0x51, RUNNABLE)
                            sun.nio.ch.NioSocketImpl.read(NioSocketImpl.java:346)
                            com.widen.search.SolrClient.query(SolrClient.java:210)

             Heap Full: G1 Old Gen holds 97% of its max, 96% after its last collection
                        Top allocators: "import-7" (0x33) 412 MB/s
                                        "qtp-exec-4" (0x49) 96 MB/s

          Current Time: 11/20/2026 09:30:00 CST
```

## Why add it

- **It works where `jstack` cannot.** Anyone who can reach the URL gets the
  dump: no shell, no JDK tools in the image, no attach permission. It also
  works from code, so a health check or an error handler can log a dump.
- **It diagnoses, not just dumps.** Nine checks read the JVM and list what looks
  abnormal, most serious first, with the thread names, locks, pools, and frames
  to act on. A reader in a hurry starts at the top instead of scrolling through
  hundreds of stacks.
- **It shows what is happening now.** The dump samples the JVM for one second,
  so it reports which threads use CPU, which allocate memory, and how much time
  garbage collection takes during that second, not as a lifetime average.
- **Stacks are short enough to read.** The dump hides reflection, generated
  proxy, and server dispatch frames, so a 60-frame request stack shows the
  application's own calls.
- **It is safe to leave in production.** The generator has no runtime
  dependencies and runs on Java 8 and later. A dump is immutable and safe to
  share between threads. System properties and main arguments that look like
  secrets print as `****`.

The cost is time: each dump takes one sampling window, one second by default.
See [The sampling window](#the-sampling-window) to change it, and
[secure `/td`](#securing-td) so that only trusted callers can reach it.

## Quick start

The library has two artifacts, published to Maven Central under the group
`widen`:

- `jvm-threaddump-generator` builds the dump. It has no dependencies.
- `jvm-threaddump-servlet` serves it. It needs `javax.servlet:javax.servlet-api`
  3.0.1 through 4.x from your container or application. Jakarta containers
  (Servlet 5 and later, such as Tomcat 10+ and Jetty 11+) are not supported.

```kotlin
dependencies {
    implementation("widen:jvm-threaddump-servlet:3.0.0")
}
```

Map the servlet in `web.xml`:

```text
<web-app>
  <servlet>
    <servlet-name>threaddump</servlet-name>
    <servlet-class>com.widen.util.td.ThreadDumpServlet</servlet-class>
  </servlet>
  <servlet-mapping>
    <servlet-name>threaddump</servlet-name>
    <url-pattern>/td</url-pattern>
  </servlet-mapping>
</web-app>
```

Or generate a dump in code:

```java
import com.widen.util.td.JvmThreadDump;

String dump = JvmThreadDump.create().generate();
```

Two request parameters change one servlet dump:

- `/td?window=5` samples for five seconds. The window is a whole number of
  seconds from 0 to 30, and `window=0` returns at once without sampling. Any
  other value gets status 400 with the body
  `window must be a whole number of seconds from 0 to 30`.
- `/td?frames=all` prints every stack frame. Any other value gets status 400
  with the body `frames must be all`.

## Securing `/td`

`ThreadDumpServlet` checks no credentials. Anyone who reaches the path gets the
whole dump: thread names and stacks that show your code's structure, host and
JVM details, system properties, main arguments, and your custom values. The dump
masks system properties and `key=value` main arguments that look like secrets,
but the masking matches key and value patterns, so a secret under an unusual
name prints in full. So does a main argument without `=`, such as a secret that
follows its flag as a separate argument (`--password hunter2`). Never put a
secret in `customValues`. Each request also holds a container thread for its
window, up to 30 seconds, so an open `/td` lets anyone tie up request threads.

Use one or more of these:

- **Require a role in `web.xml`.** The container then authenticates the caller
  against its own user store, such as a Jetty `LoginService` or a Tomcat
  `Realm`:

  ```text
  <security-constraint>
    <web-resource-collection>
      <web-resource-name>threaddump</web-resource-name>
      <url-pattern>/td</url-pattern>
    </web-resource-collection>
    <auth-constraint>
      <role-name>admin</role-name>
    </auth-constraint>
    <user-data-constraint>
      <transport-guarantee>CONFIDENTIAL</transport-guarantee>
    </user-data-constraint>
  </security-constraint>
  <login-config>
    <auth-method>BASIC</auth-method>
  </login-config>
  <security-role>
    <role-name>admin</role-name>
  </security-role>
  ```

  `CONFIDENTIAL` makes the container refuse or redirect plain HTTP, which BASIC
  authentication needs, since it sends the password in every request. When TLS
  ends at a load balancer, configure the container to trust the balancer's
  `X-Forwarded-Proto` header, or the container treats every request as plain
  HTTP.
- **Require the role in code** when you register the servlet in code, as
  [Customization](#customization) shows:

  ```java
  import javax.servlet.HttpConstraintElement;
  import javax.servlet.ServletRegistration;
  import javax.servlet.ServletSecurityElement;
  import javax.servlet.annotation.ServletSecurity.TransportGuarantee;

  ServletRegistration.Dynamic registration = event.getServletContext()
          .addServlet("threaddump", new MyAppThreadDumpServlet("cheese", "test"));
  registration.addMapping("/td");
  registration.setServletSecurity(new ServletSecurityElement(
          new HttpConstraintElement(TransportGuarantee.CONFIDENTIAL, "admin")));
  ```

- **Block the path at the edge.** Deny `/td` in the public ingress, load
  balancer, or reverse proxy, and reach it only from inside the network. In
  Kubernetes, `kubectl port-forward pod/POD 8080:8080` followed by
  `curl localhost:8080/td` reaches one pod directly, which also picks the
  instance you mean to inspect.
- **Reuse your application's own authentication.** Map `/td` under a path that
  an existing servlet filter already protects, such as your admin pages.

## Reading the findings

The `Findings` block opens the dump. Each finding gives its evidence. Each raw
line that a finding refers to ends with ` <<< ` and the finding's title, so the
eye lands on it:

```text
            G1 Old Gen: 97%/96%       init=...  used=3,931 MB  ...  <<< Heap Full
"worker-3" priority=5 id=0x1f ... BLOCKED (waiting on 0x2a for ...) <<< Deadlock
```

When no check flags anything, the block is one line:

```text
              Findings: None (9 checks, sampled over 1.0s)
```

`None` means the checks flagged nothing. It does not mean the JVM is healthy:
the checks look for a fixed set of problems at fixed thresholds. The count
covers the checks that ran. A check whose data is unavailable skips itself and
leaves the count: `CPU Saturated` when the JVM reports no CPU load,
`File Descriptors` when the JVM's operating system bean is not a Unix one, and
`Hot Threads` when per-thread CPU time is unsupported or disabled, or when no
window ran. A `jlink` runtime image built without the `jdk.management` module
reports neither CPU load nor file descriptors, and the dump has no system
memory, JVM CPU time, or allocation figures there.

### The checks

| Check | Needs a window | Flags when |
| --- | --- | --- |
| Deadlock | no | The JVM reports deadlocked threads. Each cycle of threads that wait on each other is one finding, with the number of other threads that wait behind it. |
| Lock Pile-up | no | 5 or more threads wait on locks that lead back to one holder. |
| Stack Pile-up | no | 10 or more threads that are not idle are in the same call. |
| Heap Full | no | An old-generation heap pool holds 90% or more of its max now and held 90% or more after its last collection. |
| Metadata Full | no | `Metaspace`, `Compressed Class Space`, the code cache (`Code Cache` or `CodeCache`), or a `CodeHeap` pool holds 90% or more of a defined max. |
| GC Overhead | uses one | Garbage collection took 25% or more of the window and at least 500 ms, or 10% or more of JVM uptime after the first 5 minutes. |
| CPU Saturated | uses one | The system CPU load is 0.90 or higher. |
| File Descriptors | no | The open file descriptors are 90% or more of the limit. |
| Hot Threads | yes | A thread used 50% or more of one core during the window. Needs per-thread CPU time to be supported and enabled. |

The checks are tuned to stay quiet on a healthy JVM, because a block that flags
something on every dump teaches people to skip it:

- **Waiting on a lock.** A thread waits on a lock when another thread holds the
  lock and the thread is `BLOCKED`, or is parked for a lock such as a
  `ReentrantLock`. A thread inside `Object.wait()` waits for a notify, not for a
  lock, and does not count. A thread in or behind a deadlock belongs to
  `Deadlock` and stays out of `Lock Pile-up` and `Stack Pile-up`.
- **Stack Pile-up** groups threads by the top frame of the stack and the first
  frame outside the JDK that the frame filter keeps, so it names the library a
  thread is stuck in, such as a drained connection pool. Idle threads stay out
  of the count. A `WAITING` or `TIMED_WAITING` thread is idle when no
  application code is on its stack, such as a server worker waiting for a job. A
  `RUNNABLE` thread is idle when its top frame is a selector, poll, or accept
  wait, such as `sun.nio.ch.EPoll.wait` under a Netty or XNIO event loop,
  whatever the frames below it. A `BLOCKED` thread is never idle. The thread
  that generates the dump and every thread counted in a `Lock Pile-up` also stay
  out of the count. The finding prints the count, the count per thread state,
  the top frame, the library frame when it differs from the top frame, and the
  first three thread names. It marks no lines.
- **Heap Full** checks old-generation pools: heap pools whose name contains
  `old` or `tenured` in any case, such as `G1 Old Gen` or OpenJ9's
  `tenured-SOA`, and the JVM's only heap pool, such as the one that Shenandoah
  or non-generational ZGC reports. It needs both readings at 90%. The current
  usage includes garbage, so a healthy Parallel or Serial old generation passes
  90% before every full collection. The usage after the last collection can be
  hours old. The check skips a pool with no after-collection reading or no
  defined max.
- **GC Overhead** leaves out collectors that report concurrent work instead of
  pauses (names that contain `Cycles` or `Concurrent`). The lifetime measure is
  an average since the JVM started. It runs with or without a window, but only
  once the JVM has run for 5 minutes, because collections at startup inflate the
  average of a younger JVM.
- **Hot Threads** lists up to five threads, highest first, with the top three
  frames of each stack. A thread that started during the window counts its
  whole CPU time as the window's.
- **Top allocators** under `Heap Full` and `GC Overhead` list up to three
  threads that allocated 1 MB per second or more during the window. They appear
  only when the dump sampled a window and the JVM supports per-thread
  allocation figures, so a zero window prints none.

The thresholds and the check list are fixed. A consumer cannot change them or
add its own checks.

## The sampling window

To report what the JVM is doing now, `generate()` reads per-thread CPU time,
allocated bytes, and collector totals, waits for the window, reads them again,
and reports the difference. The wait makes every dump take one window longer.

- The default window is one second. `generate()` blocks for that time.
- `Builder.sampleWindow(Duration)` sets another window. It throws
  `NullPointerException` for null and `IllegalArgumentException` for a negative
  duration. It has no upper limit.
- `sampleWindow(Duration.ZERO)` skips the wait, so `generate()` returns at once.
  The header then says `not sampled` in place of `sampled over 1.0s`, and
  `Hot Threads`, the one check that needs a window, does not run and does not
  count: `Findings: None (8 checks, not sampled)`.
- A thread that is already interrupted when it calls `generate()` skips the
  window, and the dump reads `not sampled`. An interrupt during the window ends
  the wait early and also leaves the dump `not sampled`, so no rate covers a
  partial window and the `CPU Load` line reads `Not Available`. `generate()`
  sets the interrupt flag again and finishes the dump.
- The servlet's `window` parameter stops at 30 seconds, so a request thread
  never sleeps longer than a typical proxy timeout. Each `/td` request holds its
  container thread for the whole window. The servlet allows any number of
  concurrent dumps, so expose `/td` only to trusted callers (see
  [Securing `/td`](#securing-td)).

CPU load has two caveats. `getSystemCpuLoad()` reports the load since the
previous call by any caller in the JVM. With a window, the dump reads it at the
window's start and again at its end, so the figure covers the window, or less
when another caller, such as a metrics scrape or a second dump, reads the load
during the window. With a zero window, the `CPU Saturated` check and the
`CPU Load` line use one reading, which covers the time since the previous caller
read the load. In a container with a CPU limit, the JVM reports use of that
limit instead of the host's load, so 0.90 means the container is close to being
throttled.

## The frame filter

Most frames in a stack are plumbing. The thread list hides them, so the
application's own calls stand out. A frame is hidden when its class matches one
of these rules:

| Kind | Classes |
| --- | --- |
| Reflection and method handles | `sun.reflect.`, `jdk.internal.reflect.`, `java.lang.reflect.`, `java.lang.invoke.` |
| Generated proxies | a class with no package whose name starts with `$` (a Tapestry service proxy or its advice invocation), `com.sun.proxy.`, `jdk.proxy`, and any class whose name contains `$$` (CGLIB, Javassist, Hibernate proxies, and lambda classes) |
| Tapestry | everything under `org.apache.tapestry5.` |
| Server dispatch | `org.eclipse.jetty.`, `org.glassfish.jersey.`, `org.glassfish.hk2.`, `org.jvnet.hk2.`, `javax.servlet.` |

The top frame of a stack always prints, whatever its class, because it says what
the thread is doing now. One line after the last frame gives the number of
hidden frames, and the dump omits the line when no frame is hidden:

```text
"qtp-exec-12" priority=5 id=0x51 group=main cpu=412ms block_cnt=0 wait_cnt=3 RUNNABLE
    sun.nio.ch.NioSocketImpl.read(NioSocketImpl.java:346)
    org.postgresql.jdbc.PgPreparedStatement.executeQuery(PgPreparedStatement.java:134)
    com.widen.assets.AssetDao.find(AssetDao.java:88)
    com.widen.api.AssetResource.get(AssetResource.java:57)
    java.lang.Thread.run(Thread.java:840)
    ... 47 frames hidden
```

- `Builder.allFrames(true)` and `/td?frames=all` print every frame in the thread
  list, with no count line.
- A finding always prints filtered frames as evidence, so the same JVM state
  gives the same findings whatever the setting.
- The rules are fixed. A consumer cannot add its own.

## Customization

Build a customized dump with `JvmThreadDump.builder()`:

- `mainArguments(List<String>)` prints the program arguments, such as the `args`
  of `main`. A `key=value` argument that looks like a secret prints as
  `key=****`. The default is an empty list.
- `customValues(Map<String, ?>)` adds environment-specific lines for
  debugging, such as the application name or the deploy environment. Each entry
  prints as one titled line, in the map's iteration order.
  `String.valueOf(...)` formats each value on every `generate()` call, so a
  value that changes between calls shows its current text.
- `sampleWindow(Duration)` sets how long `generate()` samples the JVM. The
  default is one second, and zero skips sampling.
- `allFrames(boolean)` prints every stack frame in the thread list when true.
  The default is false.

```java
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.widen.util.td.JvmThreadDump;

Map<String, Object> values = new LinkedHashMap<>();
values.put("App", "cheese");
values.put("Environment", "test");

JvmThreadDump dump = JvmThreadDump.builder()
        .mainArguments(Arrays.asList(args))
        .customValues(values)
        .build();
String out = dump.generate();
```

The builder copies the list and the map, so later changes to them leave the
dump unchanged.

`toBuilder()` returns a builder that holds a dump's main arguments, custom
values, window, and frame setting. Use it to build a one-off variant without
repeating the settings:

```java
import java.time.Duration;

String detailed = dump.toBuilder()
        .sampleWindow(Duration.ofSeconds(5))
        .allFrames(true)
        .build()
        .generate();
```

To serve a customized dump, extend `ThreadDumpServlet` and pass the built dump
to its constructor. The servlet regenerates the dump on every request.

```java
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.widen.util.td.JvmThreadDump;
import com.widen.util.td.ThreadDumpServlet;

public class MyAppThreadDumpServlet extends ThreadDumpServlet
{
    public MyAppThreadDumpServlet(String appName, String env) {
        super(JvmThreadDump.builder()
                .mainArguments(Arrays.asList("--app", appName))
                .customValues(values(appName, env))
                .build());
    }

    private static Map<String, Object> values(String appName, String env) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("App", appName);
        values.put("Environment", env);
        return values;
    }
}
```

A container instantiates a servlet from `web.xml` only through a public no-arg
constructor. Register a servlet that needs constructor arguments in code:

```java
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

public class ThreadDumpRegistration implements ServletContextListener
{
    @Override
    public void contextInitialized(ServletContextEvent event) {
        event.getServletContext()
                .addServlet("threaddump", new MyAppThreadDumpServlet("cheese", "test"))
                .addMapping("/td");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
    }
}
```

## Glossary

- `Hostname`: `System.getenv('HOSTNAME')`
- `File Descriptors`: The open count and the limit, from the JVM's Unix
  operating system bean. `Not Available` where the JVM has no such bean, such as
  on Windows.
- `CPU Load`:
  [System](https://docs.oracle.com/javase/8/docs/jre/api/management/extension/com/sun/management/OperatingSystemMXBean.html#getSystemCpuLoad--),
  [JVM](https://docs.oracle.com/javase/8/docs/jre/api/management/extension/com/sun/management/OperatingSystemMXBean.html#getProcessCpuLoad--)
- `System Uptime`: Linux only; parsed from file `/proc/uptime`
- `Deadlocked Threads`: The threads in the deadlock cycles, each by name and
  id. `None` when the JVM reports no deadlock.
- `Thread Count`: The number of live threads, then the count per state.
- `Main Arguments`: Empty unless the builder sets `mainArguments`. A `key=value`
  argument follows the masking rule of `JVM System Props`.
- `JVM System Props`: The `-D` values print as `****` when the key contains
  `password`, `pass`, `pwd`, `secret`, `token`, `credential`, or `key`, when the
  value contains one of those words followed by `=`, or when the value holds a
  URL password (`://user:password@` before the next `/`).

## Example Output

```text
              Findings: None (9 checks, sampled over 1.0s)

          Current Time: 11/20/2026 12:11:21 CST
     Default Time Zone: Central Standard Time (America/Chicago)
          Java Version: 17.0.14+7 (Eclipse Adoptium)
               Java VM: 17.0.14+7 (mixed mode, sharing)
             Java Home: /Users/uriah/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.14+7/Contents/Home
     Working Directory: /Users/uriah/dev/jvm-threaddump/generator
        Temp Directory: /var/folders/81/glyvgg9j0z54gwv06_7tcpr80000gp/T/

        Main Arguments:
      Default Encoding: UTF-8
      JVM System Props: file.encoding=UTF-8
                        user.country=US
                        user.language=en
                        user.variant

              Hostname: Not Available
      Operating System: Mac OS X (27.2)
     System Processors: 10 aarch64
         System Memory: 1,973 MB available; 65,536 MB total
      File Descriptors: 62 open; 1,048,576 limit
              CPU Load: 0.5499 system; 0.0078 jvm (0.0 to 1.0)
         System Uptime: Not Available
      System Idle Time: Not Available
            JVM Uptime: 0 days, 0:00:01
          JVM CPU Time: 0 days, 0:00:00

       JVM Free Memory: 496 MB (of committed)
      JVM Maximum Heap: 512 MB
       JVM Memory Args: -Xmx512m

                Memory: used/committed, used/max
                  Heap: 3%/3%         init=512 MB      used=14 MB       commit=512 MB      max=512 MB
              Non Heap: 82%/0%        init=7 MB        used=15 MB       commit=19 MB       max=-1 bytes

             Collector: used/committed, used/max
         G1 Eden Space: 17%/0%        init=27 MB       used=8 MB        commit=48 MB       max=-1 bytes
     G1 Survivor Space: 95%/0%        init=0 bytes     used=3 MB        commit=4 MB        max=-1 bytes
            G1 Old Gen: 0%/0%         init=485 MB      used=1 MB        commit=460 MB      max=512 MB
Compressed Class Space: 91%/0%        init=0 bytes     used=1 MB        commit=1 MB        max=1,024 MB
             Metaspace: 97%/0%        init=0 bytes     used=9 MB        commit=9 MB        max=-1 bytes

GC G1 Young Generation: spent 0:00:00.001 doing 1 collections
  GC G1 Old Generation: spent 0:00:00.000 doing 0 collections

    Deadlocked Threads: None
          Thread Count: 9 (5 RUNNABLE, 3 WAITING, 1 TIMED_WAITING)

"Finalizer" daemon priority=8 id=0x3 group=system cpu=0ms block_cnt=1 wait_cnt=2 WAITING (waiting for java.lang.ref.ReferenceQueue$Lock@59e505b2)
    java.lang.Object.wait(Object.java)
    java.lang.ref.ReferenceQueue.remove(ReferenceQueue.java:155)
    java.lang.ref.ReferenceQueue.remove(ReferenceQueue.java:176)
    java.lang.ref.Finalizer$FinalizerThread.run(Finalizer.java:172)

"Reference Handler" daemon priority=10 id=0x2 group=system cpu=0ms block_cnt=1 wait_cnt=0 RUNNABLE
    java.lang.ref.Reference.waitForReferencePendingList(Reference.java)
    java.lang.ref.Reference.processPendingReferences(Reference.java:253)
    java.lang.ref.Reference$ReferenceHandler.run(Reference.java:215)

"Signal Dispatcher" daemon priority=9 id=0x4 group=system cpu=0ms block_cnt=0 wait_cnt=0 RUNNABLE

"Test worker" priority=5 id=0x1 group=main cpu=309ms block_cnt=1 wait_cnt=2 RUNNABLE
    sun.management.ThreadImpl.dumpThreads0(ThreadImpl.java)
    sun.management.ThreadImpl.dumpAllThreads(ThreadImpl.java:528)
    sun.management.ThreadImpl.dumpAllThreads(ThreadImpl.java:516)
    com.widen.util.td.JvmSnapshot.read(JvmSnapshot.java:106)
    com.widen.util.td.JvmThreadDump.generate(JvmThreadDump.java:76)
    com.widen.util.td.TestDump.printsEverySectionOfTheRunningJvm(TestDump.java:15)
    org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:786)
    org.junit.platform.commons.support.ReflectionSupport.invokeMethod(ReflectionSupport.java:514)
    org.junit.jupiter.engine.execution.MethodInvocation.proceed(MethodInvocation.java:60)
    ...
    worker.org.gradle.process.internal.worker.GradleWorkerMain.main(GradleWorkerMain.java:74)
    ... 26 frames hidden
```

This output is trimmed. It leaves out three `CodeHeap` pools, five of the nine
threads, and most of the `Test worker` stack, which the lone `...` stands for.
The hidden-frame count is the real one.

## Build

Install the tools, then run the tests on Java 17 and Java 8:

```sh
mise install
./gradlew check
```

`mise.toml` installs Java 21, which runs Gradle, and Corretto 8, which it
exposes to Gradle through the `JDK8` variable. The build compiles with a Java
17 toolchain and `--release 8`, so the JARs run on Java 8 and later. `test`
runs the tests on Java 17, and `testOnJava8` runs them on Java 8. Gradle
downloads a missing JDK.

Dependencies resolve from Maven Central, so building and testing need no
credentials. The version lives in `gradle.properties`.

## License

Apache, Version 2.0
