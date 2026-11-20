# Surface abnormal JVM state as findings

- **Date:** 2026-11-20
- **Repo:** `Widen/jvm-threaddump`
- **Stack:** `modernize-3-findings`, the layer above
  `modernize-2-testable-refactor`

## Intent

Staff pull the dump during an incident to troubleshoot JVM-level problems such
as deadlocks and memory contention. The dump prints raw data: every number and
every stack looks the same whether it is healthy or not. A deadlock prints as
two hex ids, a pool at 98% of its maximum prints like a pool at 5%, and nothing
counts how many threads wait on one lock.

A person in a hurry should not have to read the whole dump to find what is
wrong. The dump does the first pass of diagnosis: it opens with a short list of
findings, each one saying "this looks like a problem you should know about" with
enough evidence to act on, and it marks the matching raw lines below.

## Goals

- Open the dump with a `Findings` block that lists what looks abnormal, most
  serious first, and says how many checks ran.
- Give each finding its evidence: the thread names, the lock, the holder, the
  pool, and the frame a thread is stuck at.
- Mark each raw line a finding refers to, so the eye lands on it.
- Sample the JVM over a short window, so the dump can report what is happening
  now: which threads use CPU, which allocate memory, and how much time GC takes.
- Let the caller set the window per dump, and let a servlet user set it with a
  URL parameter.
- Hide the stack frames that tell the reader nothing, such as reflection,
  generated proxies, and server dispatch, so the application's own calls stand
  out.

## Non-goals

- Configurable thresholds or check lists. Thresholds are fixed and documented.
- A public API for custom checks.
- Remembering anything between calls. Each `generate()` stands alone.
- A machine-readable format such as JSON.
- Java 9+ APIs, including virtual threads. `--release 8` still applies.

## Anti-goals

- **False alarms.** A block that flags something on every healthy dump teaches
  staff to skip it. A check that cannot tell normal from abnormal stays out;
  that is why low system memory, thread count, and "stuck for N minutes" have no
  check.
- **A finding that disagrees with the raw data.** A finding and the raw line it
  marks come from the same reading of the JVM.
- **"None" read as "healthy".** An empty block says the checks flagged nothing,
  and names how many checks ran. It does not say the JVM is fine.

## Constraints

- All code compiles with `options.release = 8` and every test passes on `test`
  (Java 17) and `testOnJava8`.
- The generator keeps zero runtime dependencies.
- `JvmThreadDump` stays immutable and safe to share between threads. A request
  that needs other settings builds its own dump from the shared one.
- The existing sections keep their order, titles, and 22-column title padding.
  The `Findings` block goes above them. Lines change only as listed under
  Changes to existing lines.
- Everything the checks read comes through `JvmSources`, so tests supply fixed
  data. The window's pause and its elapsed-time reading also come through
  `JvmSources`, so tests do not sleep.
- mica is the only known consumer, and its `java17-alpha` line takes this
  release. That line runs Java 17, Jetty 12.1 with the EE8 (`javax.servlet`)
  modules, Jersey 2.48, HikariCP 7.1, and Tapestry 5.9.1 (`tapestry-ioc`,
  `tapestry-http`, and `tapestry-jpa`). The idle-thread rule, the stack pile-up
  grouping, and the frame filter below are written for those libraries, with
  package prefixes that also match the older versions on mica's `master`
  (Jetty 9.4, Tapestry 5.4.1). On Java 17 a socket read shows as
  `sun.nio.ch.NioSocketImpl`, not `SocketInputStream.socketRead0`.

## Approaches

1. **Snapshot checks only.** Compute findings from the data one `generate()`
   already reads. `generate()` stays instant. It cannot say which thread uses
   CPU now or whether GC is thrashing now; a lifetime GC average can miss a
   recent spike.
2. **Snapshot plus a sampling window (chosen).** Read per-thread CPU time,
   per-thread allocated bytes, and GC totals, pause for the window, read them
   again, and report the difference. Every dump takes one window longer, on a
   JVM that may already be struggling. It answers the CPU and allocation
   questions that approach 1 cannot.
3. **Remember the previous call.** Report the change since the last
   `generate()`. This makes `JvmThreadDump` stateful, and with several pods
   behind a load balancer each request reaches a different instance, so the
   difference means little. Rejected.

Uriah chose approach 2, with the window settable per dump and from the
servlet's URL.

### Default window

1. **One second (chosen).** A plain `/td` shows the sampled findings with no
   parameter to remember. Every dump takes one second longer, including
   `JvmThreadDump.create().generate()` in library code.
2. **Zero.** A plain `/td` stays instant and prints a hint to add the
   parameter. A reader in a hurry misses the hint.

Uriah chose one second. The feature exists so nobody has to know what to ask
for, and 3.0.0 is unreleased and already changes the API.

## What the reader sees

The `Findings` block is the first thing in the dump, above `Current Time`.

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

With nothing flagged, the block is one line:

```text
              Findings: None (9 checks, sampled over 1.0s)
```

With a zero window, the header says `not sampled` in place of
`sampled over 1.0s`, and Hot Threads, the one check that needs a window, does
not run and does not count: `Findings: None (8 checks, not sampled)`.

Each raw line a finding refers to ends with a space, `<<<`, a space, and the
finding's title:

```text
            G1 Old Gen: 97%/96%       init=...  used=3,931 MB  ...  <<< Heap Full
"worker-3" priority=5 id=0x1f ... BLOCKED (waiting on 0x2a for ...) <<< Deadlock
```

Findings print in the order of the Checks table below, which is most serious
first. Within one check, the largest group or the highest figure prints first.

## Checks

| # | Title | Needs window | Flags when |
| --- | --- | --- | --- |
| 1 | Deadlock | no | `findDeadlockedThreads()` reports threads |
| 2 | Lock Pile-up | no | 5 or more threads wait on locks that lead back to one holder |
| 3 | Stack Pile-up | no | 10 or more threads that are not idle are in the same call |
| 4 | Heap Full | no | an old-generation pool holds 90% or more of its max now, and held 90% or more after its last collection |
| 5 | Metadata Full | no | Metaspace, Compressed Class Space, or a code pool holds 90% or more of a defined max |
| 6 | GC Overhead | uses it | GC took 25% or more of the window and at least 500 ms, or 10% or more of JVM uptime after the first 5 minutes |
| 7 | CPU Saturated | uses it | system CPU load is 0.90 or higher |
| 8 | File Descriptors | no | open descriptors are 90% or more of the limit |
| 9 | Hot Threads | yes | a thread used 50% or more of one core during the window |

Checks 6 and 7 always run and count; check 9 runs and counts only with a window
above zero. That gives 9 checks with a window and 8 without. A check that is
skipped because its data is unavailable, as checks 7, 8, and 9 can be, lowers
the count by one.

Checks 1 and 2 share one definition. A thread **waits on an owner** when
`ThreadInfo.getLockOwnerId()` names another thread and the thread is either
`BLOCKED`, or `WAITING` or `TIMED_WAITING` with a top frame outside
`java.lang.Object`. The second case is a thread parked for a lock such as a
`ReentrantLock`. A thread inside `Object.wait()` also reports the monitor's
current holder as its owner, but it waits for a notify, not for the lock, so
it does not count.

Details per check:

1. **Deadlock.** `findDeadlockedThreads()` is only the trigger. On Java 8 it
   can also return a thread that waits behind the deadlock, so the check finds
   the real cycles itself: a thread is in a cycle when following owners from it
   leads back to it. Each cycle is one finding. For each thread in the cycle,
   print its name and id, the lock it waits for, the name of the thread that
   holds it, and the top frame of its stack. Then print one line counting the
   other threads that wait, directly or through other waiters, on a thread in
   the cycle; the line is left out at zero. Marks the header of each thread in
   the cycle.
2. **Lock Pile-up.** Follow each waiting thread's owner to the thread at the
   end of the chain, the holder, which waits on no owner itself. Flag a holder
   with 5 or more threads behind it. Print the count, the holder's name, id,
   and state, and the top 3 frames of its stack. Threads in or behind a
   deadlock belong to check 1 and are left out here. Marks the holder's header.
3. **Stack Pile-up.** Group threads by two frames: the top frame of the stack
   and the first frame outside the JDK (outside `java.`, `javax.`, `jdk.`,
   `sun.`, and `com.sun.`). The top frame alone is not enough: every thread
   that waits for a Hikari connection and every thread that reads a socket has
   only JDK frames on top. The first frame outside the JDK names the library
   the thread is stuck in, such as `ConcurrentBag.borrow` for a drained Hikari
   pool, or the JDBC driver's read for a slow database. Frames compare by class
   and method, ignoring line numbers. A thread with no frame outside the JDK
   groups by its top frame alone. Leave out idle threads, the thread generating
   the dump, and threads already counted in checks 1 and 2. Flag a group of 10
   or more. Print the count, a count per thread state, the two frames, and the
   first 3 thread names in dump order. Marks nothing; the group is too large to
   mark usefully.

   A thread is **idle** in one of three cases. It is `WAITING` or
   `TIMED_WAITING` and every frame of its stack belongs to the JDK or to a
   server or pool library, so no application code is on the stack. It is
   `RUNNABLE` and its top frame's class and method name a known selector, poll,
   or accept wait, whatever the frames below it, such as
   `sun.nio.ch.EPoll.wait` under a Netty or XNIO event loop; the waits are one
   set of `class#method` keys that covers the Linux, macOS, and Windows
   selectors on Java 8 and later, the accept calls, and Netty's native
   transports. Or its stack is empty. A `BLOCKED` thread is never idle. The
   package prefixes of the library rule are `java.`,
   `javax.`, `jdk.`, `sun.`, `com.sun.`, `org.eclipse.jetty.`,
   `org.apache.tomcat.`, `org.apache.catalina.`, `io.netty.`, and
   `com.zaxxer.hikari.`.
4. **Heap Full.** An old-generation pool is a heap pool whose name contains
   `old` or `tenured` in any case, as in OpenJ9's `tenured-SOA`, or the only
   heap pool the JVM reports, as with
   Shenandoah's `Shenandoah` pool and non-generational ZGC's `ZHeap`. Flag it
   when both readings are at 90% or more of its max: its current usage, and its
   usage after its last collection (`MemoryPoolMXBean.getCollectionUsage()`).
   Neither reading is enough alone. Current usage includes garbage, so a
   Parallel or Serial old generation passes 90% before every full collection on
   a healthy JVM. The after-collection reading updates only when an
   old-generation collection runs (on Java 8, only a full collection, even for
   G1), so it can be hours old, and on Java 17 G1 it can stay far above what the
   pool holds now. Requiring both means the finding never disagrees with the
   pool line it marks. A pool with no after-collection reading (null, or zero
   bytes used) is not flagged: a heap that is really full forces an
   old-generation collection, which sets the reading. A pool with an undefined
   max is skipped. Print both percentages. When a window ran, add the top 3
   allocating threads with their MB per second. Marks the pool's line.
5. **Metadata Full.** The 90% rule on current usage alone, for `Metaspace`,
   `Compressed Class Space`, `Code Cache`, and the `CodeHeap` pools, when the
   pool has a defined max. Marks the pool's line.
6. **GC Overhead.** Two measures; either one flags, and the finding prints
   each one that did. The window measure, used only with a window: the sum
   over all collectors of collection time during the window is 25% or more of
   the window's elapsed time and at least 500 ms. The 500 ms floor keeps one
   ordinary pause from flagging a one-second window. Print the percentage, the
   number of collections, and the top 3 allocating threads. The lifetime
   measure, used with or without a window once the JVM has been up for 5
   minutes (300,000 ms): total collection time is 10% or more of JVM uptime,
   labeled as an average since the JVM started. Startup collections make the
   average of a younger JVM misleading. Collectors that report
   concurrent work instead of pauses are left out of both sums: those whose
   name contains `Cycles` or `Concurrent`. Marks the `GC` lines of the
   collectors that ran in the window when the window measure flagged, and all
   of the summed collectors when only the lifetime measure did.
7. **CPU Saturated.** `getSystemCpuLoad()` reports the load since the previous
   call by any caller in the JVM. With a window, call it at the window's start
   and use the value from the window's end. The figure then covers the window,
   or less when another caller, such as a metrics scrape or a second dump,
   reads the load during the window. With a zero window, the check runs on the
   one reading the `CPU Load` line prints, which covers the time since the
   previous caller. In a container with a CPU limit, the JVM reports use of
   that limit, not the host's load, so 0.90 means the container is close to
   being throttled; the readme says so. Print the system load and the JVM's
   share. Skipped, and not counted, when the load is not available. Marks the
   `CPU Load` line.
8. **File Descriptors.** From `com.sun.management.UnixOperatingSystemMXBean`.
   Skipped, and not counted, when the bean is not that type. Marks the new
   `File Descriptors` line.
9. **Hot Threads.** A thread's CPU time during the window divided by the
   window's elapsed time. List up to 5 threads at 50% or more, highest first,
   each with its percentage of one core and the top 3 frames of its stack at
   the end of the window. Skipped, and not counted, when thread CPU time is not
   supported or not enabled. Marks each listed thread's header.

A thread that started during the window counts its whole CPU time and
allocation as the window's. A thread that ended during the window is left out.
Allocation figures come from `com.sun.management.ThreadMXBean`
(`getThreadAllocatedBytes`); when that bean or the feature is unavailable, the
`Top allocators` lines are left out.

## Stack filtering

Most frames in a mica stack are plumbing. One service call through a Tapestry
IoC proxy adds a generated proxy frame and several advice frames, and every
request thread ends in about 30 Jetty and Jersey dispatch frames. The dump
hides those frames, so the application's own calls stand out.

```text
"qtp-exec-12" priority=5 id=0x51 group=main cpu=412ms ... RUNNABLE
    sun.nio.ch.NioSocketImpl.read(NioSocketImpl.java:346)
    org.postgresql.core.VisibleBufferedInputStream.readMore(VisibleBufferedInputStream.java:161)
    org.postgresql.jdbc.PgPreparedStatement.executeQuery(PgPreparedStatement.java:134)
    com.widen.assets.AssetDao.find(AssetDao.java:88)
    com.widen.assets.AssetServiceImpl.find(AssetServiceImpl.java:41)
    com.widen.api.AssetResource.get(AssetResource.java:57)
    java.lang.Thread.run(Thread.java:840)
    ... 47 frames hidden
```

A frame is **hidden** when its class matches one of these rules:

| Kind | Classes |
| --- | --- |
| Reflection and method handles | `sun.reflect.`, `jdk.internal.reflect.`, `java.lang.reflect.`, `java.lang.invoke.` |
| Generated proxies | a class with no package whose name starts with `$`, which is how Tapestry 5.4 through 5.9 names a service proxy (`$AssetService_1a2b3c4d`) and its advice invocations (`$AssetService_1a2b3c4d$Invocation_find_5e6f`); `com.sun.proxy.` and `jdk.proxy`; any class whose name contains `$$`, which covers CGLIB, Javassist, Hibernate proxies, and lambda classes |
| Tapestry | everything under `org.apache.tapestry5.`. Tapestry 5.7 spread its plumbing over `commons.internal`, `ioc.internal`, `http.internal`, `internal.plastic`, and `internal.jpa`, and mica owns no class in that package, so one prefix covers every version |
| Server dispatch | `org.eclipse.jetty.`, `org.glassfish.jersey.`, `org.glassfish.hk2.`, `org.jvnet.hk2.`, `javax.servlet.` |

The rules for printing:

- The top frame of a stack always prints, whatever its class, because it says
  what the thread is doing now.
- Hidden frames are dropped where they stand. One line after the last frame
  gives the count, `... 47 frames hidden`, and is left out at zero. A marker
  per run of hidden frames would put back the clutter the filter removes.
- The filter applies to the thread list and to the frames a finding prints as
  evidence.
- A dump built with `allFrames(true)` prints every frame in the thread list and
  no count line. Findings filter their evidence either way, so one JVM state
  gives the same findings whatever the setting.

The checks use the filter in two places. Stack Pile-up's second key frame is
the first frame outside the JDK that the filter keeps, so a proxy frame never
becomes the key. Wherever a check prints "the top 3 frames", it prints the
first 3 frames the filter keeps. The idle rule and the lock rules read the
unfiltered stack.

## Changes to existing lines

- **Deadlocked Threads:** prints the threads in the cycles check 1 found, each
  by name and id, `"worker-3" (0x1f), "worker-7" (0x2a)`, so the line agrees
  with the finding. A thread the JVM reports that is missing from the thread
  snapshot prints its id alone.
- **Thread Count:** gains a count per state, in `Thread.State` order, leaving
  out states with no threads:
  `212 (12 RUNNABLE, 43 BLOCKED, 120 WAITING, 37 TIMED_WAITING)`.
- **File Descriptors:** a new line after `System Memory`:
  `412 open; 65,536 limit`, or `Not Available`.
- **CPU Load:** with a window, the values come from the reading at the
  window's end.
- **Thread stacks:** hidden frames are left out and counted, as Stack filtering
  describes.
- **Markers:** flagged lines end with `<<< TITLE`. A line two findings refer
  to lists both titles, separated by `, `.

## API

The builder holds every setting. A caller that needs different settings for one
dump, as the servlet does per request, starts from an existing dump.

- `JvmThreadDump.Builder.sampleWindow(Duration)` sets the window. It throws
  `NullPointerException` for null and `IllegalArgumentException` for a negative
  window. It has no upper limit; a library caller chooses its own. The default
  is one second.
- `JvmThreadDump.Builder.allFrames(boolean)` turns the frame filter off for the
  thread list when true. The default is false.
- `JvmThreadDump.toBuilder()` returns a builder that holds this dump's
  settings: its main arguments, custom values, window, and frame setting.
- `JvmThreadDump.generate()` keeps its signature. It samples for the dump's
  window.
- When the calling thread is interrupted during the window, `generate` stops
  waiting, restores the interrupt flag, and finishes the dump as not sampled,
  as for a zero window, with both CPU loads unavailable.
- `ThreadDumpServlet` reads two optional request parameters. With neither, it
  serves its own dump. With either, it serves a dump built from its own with
  `toBuilder()` and the settings below.
  - `window` is a whole number of seconds from 0 to 30. Any other value gets
    status 400 and the plain-text body
    `window must be a whole number of seconds from 0 to 30`. The limit of 30
    keeps a request thread from sleeping longer than a typical proxy timeout,
    and is not configurable.
  - `frames=all` prints every frame. Any other value gets status 400 and the
    plain-text body `frames must be all`.

## Structure

Today each section reads `JvmSources` on its own, so two sections can see
different moments. Findings need the same readings the sections print, and the
sections need the findings to place markers. `generate` therefore works in
this order:

1. Read the start-of-window figures: per-thread CPU time and allocated bytes,
   GC counts and times, and the first CPU load call.
2. Pause for the window.
3. Read everything the dump prints once, into one immutable value: the thread
   snapshot, the deadlocked ids, pool usages, GC totals, OS figures, and the
   end-of-window figures.
4. Run each check against that value. A check returns its findings; each
   finding holds its title, its lines of evidence, and what it marks (thread
   ids, pool names, or a named line).
5. Render the `Findings` block, then the existing sections, which read the same
   value and add markers from the findings.

Changing the sections to read one shared value is preparatory work in this
layer. Each check is a small package-private unit that tests exercise alone.

A check that throws fails the whole dump, as any other exception in `generate`
does. See Calls made without review.

## Tests

Each behavior gets a test; variations share a parametrized test.

- **Each check with fakes:** flagged at its threshold and not flagged just
  below it, with the exact evidence lines. Lock Pile-up follows a chain of two
  owners to the holder. Stack Pile-up leaves out idle Jetty workers and threads
  already in a lock pile-up. Heap Full needs both readings high and ignores a
  pool with no after-collection reading. Deadlock reports only the cycle when
  the JVM's list also holds a thread behind it. Lock Pile-up leaves out threads
  inside `Object.wait()`. GC Overhead ignores a single 300 ms pause in a
  one-second window. Checks that depend on an unavailable bean
  are skipped and not counted.
- **Window:** with a fake pause and fixed start and end figures, Hot Threads,
  GC Overhead, and the allocator lines print the expected rates; a thread that
  starts or ends mid-window is handled as described; a zero window prints
  `not sampled` and skips Hot Threads; an interrupted pause keeps the interrupt
  flag set, returns a dump, counts as not sampled, and reports no CPU load.
- **Findings block:** none, one, and several findings in table order.
- **Markers:** each marked line type, and one line with two titles.
- **Changed lines:** deadlocked names, thread state counts, file descriptors.
- **API:** null and negative windows are rejected; the default window is one
  second; `toBuilder()` carries over the main arguments, custom values, window,
  and frame setting.
- **Stack filtering:** each kind of hidden frame, in one parametrized test; a
  hidden top frame still prints; the count line, and no line at zero;
  `allFrames(true)` prints every frame in the thread list while a finding's
  evidence stays filtered; Stack Pile-up keys on the first kept frame outside
  the JDK.
- **Servlet:** `window=5` serves a dump sampled for five seconds; `frames=all`
  serves every frame; no parameter serves the servlet's own dump; `window` of
  `-1`, `31`, `1.5`, or `abc`, and `frames=some`, get 400 with the message.
- **Real JVM:** the deadlock fixture's threads appear by name in a `Deadlock`
  finding, and the smoke test finds the `Findings` title. No real-JVM test
  asserts a CPU or GC rate, which would depend on the machine's load.

Existing tests that pin exact lines change where this design changes those
lines.

## Documentation

The readme gains a Findings section: what the block means, that "None" is not a
clean bill of health, each check with its threshold, the frame filter's rules,
the `window` and `frames` parameters, and the `sampleWindow`, `allFrames`, and
`toBuilder` methods. The example output and the glossary are updated.

## Calls made without review

These were the designer's calls, not decisions made together:

- Every threshold in the Checks table: 5 lock waiters, 10 threads in a stack
  group, 90% for pools and descriptors, 25% of a window and 10% of uptime for
  GC, 0.90 for CPU load, 50% of a core for a hot thread, and the list lengths
  of 3 and 5.
- Allocating threads print as evidence under Heap Full and GC Overhead, not as
  a finding of their own, because no fixed MB-per-second figure separates a
  busy thread from a faulty one.
- The idle rule and its package list.
- Heap Full needs both the current and the after-collection reading at 90%,
  and does not flag a pool that has no after-collection reading.
- The Deadlock check finds the cycle itself, and the `Deadlocked Threads` line
  prints that cycle instead of the JVM's list.
- Threads inside `Object.wait()` do not count as waiting on a lock.
- Stack Pile-up groups by the top frame and the first frame outside the JDK.
- GC Overhead needs at least 500 ms of collection in the window, and the
  lifetime measure also runs when a window is set and the JVM has been up for 5
  minutes.
- CPU Saturated runs with a zero window on the load since the previous caller,
  and accepts that another caller can shorten the span a window covers.
- The servlet takes whole seconds only, limits the window to 30 seconds, and
  answers 400 outside that range.
- A check that throws fails the dump. The alternative, catching the exception
  and printing the raw sections without that finding, keeps the dump available
  during an incident but hides a broken check.
- The `Findings` block sits above `Current Time`.
- The frame filter's rule list. Uriah asked to hide frames that are not
  useful, including Tapestry IoC proxies; reflection, other generated proxies,
  and the Jetty, Jersey, and servlet dispatch frames were added to that.
- Hidden frames are counted in one line per stack, the top frame always
  prints, and `frames=all` brings the hidden frames back.
- Per-request settings go through `toBuilder()` instead of arguments to
  `generate`, so a new setting needs no new overload.
- The filter's rules are fixed. A consumer cannot add its own.
- Stack Pile-up marks no lines.

## Left to the builder

- Names of the snapshot value, the check units, and the finding type.
- How `JvmSources` exposes the pause, the elapsed-time reading, and the
  `com.sun.management` beans.
- How rates and percentages round, as long as the sketches above stay
  recognizable.
- Wrapping of long evidence lines; the sketches do not wrap.
