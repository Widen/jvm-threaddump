# Trim library plumbing from stacks and share repeated stacks

- **Date:** 2026-11-24
- **Repo:** `Widen/jvm-threaddump`
- **Branch:** `modernize-5-frame-filter`, based on `master`

## Intent

A dump of `binoculars` in stage (64 threads, Java 8) still runs to about 1,000
lines with the current frame filter. Two kinds of frames make up most of it:

- **Client library pipelines.** A thread waiting on an SQS `receiveMessage` call
  shows 73 frames: 4 `java.net`, 6 `sun.security.ssl`, 22 Apache HttpClient 5,
  and 40 AWS SDK v2 frames sit between the socket read at the top and the three
  frames of application code at the bottom.
- **Idle pool threads.** About 37 threads wait in an executor, each with the
  same 8 or 9 JDK frames (`Unsafe.park`, `LockSupport.park`, the
  `AbstractQueuedSynchronizer` condition, the queue's `take`, `getTask`,
  `runWorker`, `Worker.run`, `Thread.run`). Thirteen `MariaDb-timeout` threads
  and seven `slack-api-metrics` threads repeat one stack each.

The reader wants to see what each thread is doing and where the application's
own code is. This change trims both kinds of frames and prints a repeated stack
once, with the defaults set for the most readable dump and options to turn the
grouping down or off.

## Goals

- Collapse each run of frames from one client or I/O library to its innermost
  and outermost frame.
- Hide JDK executor and lock plumbing below the top frame.
- Hide `access$NNN` accessor frames and frames that repeat the frame above.
- Print one stack for threads whose stacks are identical, in one of three
  layouts, with the shortest as the default.
- Keep the findings the same: no change to which threads `Stack Pile-up` groups
  or to any check's thresholds.

## Non-goals

- A switch per filter rule. `allFrames(true)` and `/td?frames=all` remain the
  one way to see every frame.
- Library prefixes a consumer can add to the collapse list. The rules stay
  fixed, as the README says of the current ones.
- Grouping threads inside findings. A finding's evidence uses the filtered
  frames, so it gets shorter, but its layout stays the same.
- A layout that replaces a repeated stack with `same stack as "NAME"`.

## Anti-goals

- Hiding the frame that says where a thread is stuck: a connection pool lease,
  an SDK retry backoff, a lock acquisition, or the innermost frame of a library
  call.
- A thread header that goes missing, or a finding marker that moves off its
  thread's header.
- A different group key in `Stack Pile-up` for a stack with a real library
  frame. Its key is the top frame and `StackFilter.firstKeptOutsideJdk`, and
  "Where the filter applies" names the only plumbing frames whose loss can
  change it.

## Approaches

### Library plumbing

1. **Collapse runs (chosen).** A run of consecutive frames from one listed
   library keeps its innermost and outermost frame. The innermost shows what
   the library is doing, such as `StrictConnPool.lease` or the SDK frame that
   sleeps between retries, and the outermost shows how the caller entered it.
   The first kept frame outside the JDK stays the same, so `Stack Pile-up`
   groups threads as before.
2. **Add prefixes to the hidden list.** Hiding
   `software.amazon.awssdk.core.internal.`, the SDK client handlers, and the
   HttpClient execution chain saves about as many lines with only list entries.
   Rejected: it hides the retry stages, and it changes the library frame
   `Stack Pile-up` names for an SQS wait.

### Repeated stacks

1. **Gather every thread with the same stack under its first member (chosen as
   the default, `SAME_STACK`).** The shortest dump. Threads leave name order,
   but rarely move far, because threads with the same stack almost always share
   a name prefix.
2. **Share a stack only between neighbors in name order (`NEIGHBORS`).** Keeps
   name order exactly and still merges most pools.
3. **No grouping (`NONE`).** Today's layout.
4. **Replace a repeated stack with `same stack as "NAME"`.** Rejected: never
   more readable than the first two.

Uriah chose collapsing runs, and asked for the grouping layouts as options with
the most readable as the default. The first three layouts are the options.

## Design

### The frame filter

`StackFilter.kept(stack)` applies four rules in this order. The top frame
always prints, whatever the rules say, because it says what the thread is doing
now.

1. **Hidden frames.** The current rules, plus the executor plumbing and
   accessor rules below.
2. **Repeats.** A frame whose class and method equal those of the last kept
   frame is dropped. This removes overload and bridge pairs such as
   `DelayedWorkQueue.take` twice, `SocketInputStream.read` twice, and
   `ServletContainer.doFilter` three times, and it folds deep recursion into
   one frame.
3. **Library runs.** Two or more consecutive kept frames whose classes start
   with the same collapsed prefix keep only the first and the last of the run.
   The prefixes are:

   | Library | Prefix |
   | --- | --- |
   | AWS SDK v2 | `software.amazon.awssdk.` |
   | AWS SDK v1 | `com.amazonaws.` |
   | Apache HttpClient 5 | `org.apache.hc.` |
   | Apache HttpClient 4 | `org.apache.http.` |
   | OkHttp | `okhttp3.` |
   | Okio | `okio.` |
   | Netty | `io.netty.` |
   | JDK NIO | `sun.nio.ch.` |
   | JDK TLS | `sun.security.ssl.` |
   | JDK networking | `java.net.` |

   Frames from two different prefixes form two runs, even when they sit next to
   each other.

#### Executor plumbing

These frames are hidden, matched by class and method unless the row says
otherwise:

| Frames | Why they say nothing |
| --- | --- |
| every method of `java.util.concurrent.locks.LockSupport` and of classes starting with `java.util.concurrent.locks.AbstractQueuedSynchronizer` or `java.util.concurrent.locks.AbstractQueuedLongSynchronizer` | the top frame (`Unsafe.park`) and the frame below them (`LinkedBlockingQueue.take`, `ReentrantLock.lock`) say what the thread waits for, and the header names the lock |
| `java.util.concurrent.ThreadPoolExecutor#runWorker`, `java.util.concurrent.ThreadPoolExecutor$Worker#run` | every pool thread has them; `getTask` above them marks an idle one, and the task above them marks a busy one |
| `java.util.concurrent.FutureTask#run`, `java.util.concurrent.FutureTask#runAndReset`, `java.util.concurrent.Executors$RunnableAdapter#call`, `java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask#run` | wrappers around a submitted task |
| `java.util.concurrent.ForkJoinWorkerThread#run`, `java.util.concurrent.ForkJoinPool#runWorker` | the fork-join equivalent of the worker loop |
| `java.util.concurrent.ForkJoinPool#managedBlock`, `java.util.concurrent.ForkJoinPool#unmanagedBlock` | from Java 17 on, every `ConditionObject.await` passes through them before it parks, so without this rule an idle pool thread prints five frames there instead of three; in a fork-join worker the frame above still says what blocks |
| `java.lang.Thread#run`, `java.lang.Thread#runWith` | the bottom of nearly every stack; `runWith` is the Java 21 form |
| classes starting with `io.netty.util.concurrent.SingleThreadEventExecutor$`, `io.netty.util.internal.ThreadExecutorMap$`, and `io.netty.util.concurrent.FastThreadLocalRunnable` | Netty's thread wrappers; without them the Netty run ends at `NioEventLoop.run`, which names the thread's job |

An idle scheduler thread then prints three frames:

```text
"MariaDb-timeout" daemon priority=5 id=0x70 group=main cpu=0ms block_cnt=0 wait_cnt=10 WAITING
    sun.misc.Unsafe.park(Unsafe.java)
    java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take(ScheduledThreadPoolExecutor.java:1081)
    java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1074)
    ... 6 frames hidden
```

#### Accessors

A frame whose method name is `access$` followed by digits is hidden. javac
generates these to let an inner class reach a private member; they carry no
code of their own.

#### The SQS thread after the rules

```text
"BackgroundService-SqsConsumer-EventConsumer-BirdseyeEnvironmentChangedHandler-0" daemon ... RUNNABLE
    java.net.SocketInputStream.socketRead0(SocketInputStream.java)
    java.net.SocketInputStream.read(SocketInputStream.java:171)
    sun.security.ssl.SSLSocketInputRecord.read(SSLSocketInputRecord.java:476)
    sun.security.ssl.SSLSocketImpl$AppInputStream.read(SSLSocketImpl.java:980)
    org.apache.hc.core5.http.impl.io.SessionInputBufferImpl.fillBuffer(SessionInputBufferImpl.java:149)
    org.apache.hc.client5.http.classic.HttpClient.executeOpen(HttpClient.java:183)
    software.amazon.awssdk.http.apache5.internal.impl.Apache5SdkHttpClient.executeOpen(Apache5SdkHttpClient.java:70)
    software.amazon.awssdk.services.sqs.DefaultSqsClient.receiveMessage(DefaultSqsClient.java:1996)
    com.widen.mica.sqs.impl.SqsConsumerBackgroundServiceFactory$Service.runOnce(SqsConsumerBackgroundServiceFactory.java:259)
    com.widen.mica.sqs.impl.SqsConsumerBackgroundServiceFactory$Service.run(SqsConsumerBackgroundServiceFactory.java:211)
    com.widen.mica.background.services.BackgroundServicesImpl$BackgroundServiceThread.run(BackgroundServicesImpl.java:148)
    ... 66 frames hidden
```

#### Where the filter applies

`StackFilter.kept` already feeds the thread list and the evidence frames of
`Hot Threads` and `Lock Pile-up`, so all three get the new rules. Those checks
print their first three kept frames, which now reach past the plumbing.
`Stack Pile-up` keys on the top frame and on `firstKeptOutsideJdk`, which keeps
using `kept()`, so the check names only frames the thread list shows. The
innermost frame of a library run is kept, so the key stays the same for any
stack whose first frame outside the JDK belongs to a library. It can change in
two rare cases, and the change drops a plumbing frame from the key:

- A busy thread from a Netty `DefaultThreadFactory`, running only JDK frames
  above a `FastThreadLocalRunnable`, keys on its top frame alone instead of on
  `FastThreadLocalRunnable.run`.
- An `access$NNN` accessor that calls straight into a JDK class, such as one
  javac generates for `Outer.super.method()`, is no longer the key's frame.

Keeping the old rules for `firstKeptOutsideJdk` alone would hold the key fixed
in these cases, at the cost of a second rule set and findings that name frames
the thread list hides. That trade is my call.

`StackPileUpCheck.isIdle` reads the raw stack and does not change.

### Repeated stacks

#### The option

A new public enum, `ThreadGrouping`, with three values:

- `SAME_STACK`, the default: every thread whose stack equals another's prints
  under the first of them in name order, and the group sits where that first
  thread would.
- `NEIGHBORS`: only threads next to each other in name order share a stack.
- `NONE`: every thread prints its own stack.

`JvmThreadDump.Builder.threadGrouping(ThreadGrouping)` sets it and rejects null
with a `NullPointerException`. `toBuilder()` copies it.

Two stacks are equal when they hold the same frames in the same order, line
numbers included, compared on the raw stack, whatever `allFrames` says. Equal
raw stacks print the same frames and the same hidden count. A thread with an
empty stack never groups, since it has no stack to share.

#### The layout

A group prints every member's header, in name order, each with its own finding
marker, then the shared stack and hidden-count line once, then the blank line:

```text
"MariaDb-timeout" daemon priority=5 id=0x70 group=main cpu=0ms block_cnt=0 wait_cnt=10 WAITING
"MariaDb-timeout" daemon priority=5 id=0x71 group=main cpu=0ms block_cnt=0 wait_cnt=8 WAITING
"MariaDb-timeout" daemon priority=5 id=0x72 group=main cpu=0ms block_cnt=0 wait_cnt=9 WAITING
    sun.misc.Unsafe.park(Unsafe.java)
    java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take(ScheduledThreadPoolExecutor.java:1081)
    java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1074)
    ... 6 frames hidden

"OkHttp ConnectionPool connection closer" daemon ...
```

The `Thread Count` line keeps counting threads, not groups.

#### The servlet

`ThreadDumpServlet` accepts a third request parameter, `group`, with the values
`same-stack`, `neighbors`, and `none`. Any other value gets status 400 with the
body `group must be same-stack, neighbors, or none`. `forRequest` takes the new
parameter and returns the dump itself when all three are null, as it does for
two today.

### README

- "The frame filter": add the executor, Netty wrapper, and accessor rows to the
  table, a paragraph on repeats and library runs with the prefix table, and
  replace the sample output, which ends in `java.lang.Thread.run`.
- Add a section on repeated stacks with the three layouts and the sample above.
- "Customization": add `threadGrouping(ThreadGrouping)` and its default, and
  name the grouping among the settings `toBuilder()` holds.
- The request parameters list: add `/td?group=...` and its error body.
- The annotated example dump near the end: regenerate the thread section so it
  matches the new rules.

The JavaDoc of `Builder.allFrames` lists what the filter hides; update it to
name the new kinds of frames.

## Left to the builder

- How `ThreadSection` builds the groups, and whether grouping sits in its own
  small class.
- The names of the new constants and private methods.
- The test cases, as long as each rule, each layout, the servlet parameter, and
  the unchanged `Stack Pile-up` key have one.
