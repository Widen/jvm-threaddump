# Show CPU throttling in a limited container

- **Date:** 2026-11-21
- **Repo:** `Widen/jvm-threaddump`
- **Stack:** `modernize-4-cpu-throttled`, the layer above `modernize-3-findings`

## Intent

A container with a CPU limit gets a quota of CPU time per period, 100 ms by
default. When its threads use the quota before the period ends, the kernel stops
them until the next period. That is throttling, and it shows as latency, not as
load.

The dump cannot see it. `CPU Load` is an average over the window, and
`CPU Saturated` flags only at 0.90. In a test on Corretto 17 with a one-CPU
limit, eight threads that each used 20 ms of CPU every 200 ms gave a load of
0.80 and no findings, while the kernel throttled the container in 21 of 42
periods and each 20 ms burst took 100 ms. On a JVM that predates support for the
host's cgroup version, `CPU Load` is the host's figure and says nothing about
the container at all.

The kernel counts throttled periods in the container's cgroup files. The dump
reads them, prints the limit and the count, and flags a container that is
throttled often.

## Goals

- Print the container's CPU limit and how often it was throttled, on a new
  `CPU Limit` line.
- Flag frequent throttling with a tenth check, `CPU Throttled`.
- Work on cgroup v1 and v2, and on any JVM, including one that reports the
  host's CPU figures.
- Leave a dump outside a limited container unchanged: no new line, and the same
  count of checks.

## Non-goals

- A JVM that runs as a systemd service with `CPUQuota`, or in any nested cgroup
  that is not mounted at the paths below.
- Throttled time. `throttled_usec` sums across CPUs, so it exceeds wall time and
  misleads beside a window length.
- Memory, pids, I/O, or pressure figures from the cgroup.
- Contention without a limit (CPU shares or weight). The kernel counts nothing
  for it outside the pressure files.
- A changed `CPU Saturated` check.

## Anti-goals

- A finding on every dump of a healthy container. One throttled period, or
  throttling during startup, does not flag.
- A failed dump because a cgroup file is missing, unreadable, or in an
  unexpected format. The line and the check are absent instead.

## Approaches

1. **Fixed container paths (chosen).** Read the cgroup files where a container
   runtime mounts the container's own cgroup. This covers Docker, Kubernetes,
   and ECS in about 60 lines. It misses a JVM in a nested cgroup on a plain
   host.
2. **Resolve the process's own cgroup** from `/proc/self/cgroup` and
   `/proc/self/mountinfo`, as the JDK does. This also covers systemd services,
   at about three times the code and many more test fixtures.
3. **Reflect into `jdk.internal.platform.Metrics`.** Rejected: every consumer
   would have to pass `--add-exports`.

Uriah chose the first. mica, the only known consumer, runs in containers.

## Design

### Reading the files

`JvmSources` gains one method that returns a file's text, or empty when the file
is missing or unreadable. `procUptime()` becomes a call to it with
`/proc/uptime`. Tests put file contents in a map on `FakeJvm`.

The dump tries cgroup v2 first, then v1 in two directories, and uses the first
whose limit file is readable:

| Version | Limit | Counters |
| --- | --- | --- |
| v2 | `/sys/fs/cgroup/cpu.max`: `QUOTA PERIOD` in microseconds, where a quota of `max` means no limit | `/sys/fs/cgroup/cpu.stat`: `nr_periods`, `nr_throttled` |
| v1 | `cpu.cfs_quota_us` (`-1` means no limit) and `cpu.cfs_period_us`, in `/sys/fs/cgroup/cpu/` or else `/sys/fs/cgroup/cpu,cpuacct/` | `cpu.stat` in the same directory: `nr_periods`, `nr_throttled` |

The limit in CPUs is the quota divided by the period. Without a limit, or when a
file does not parse, the dump has no CPU limit: it prints no line and the check
does not apply.

### In the snapshot

The counters are read with the other counters at each end of the sampling
window, so the window figures are the difference. `JvmSnapshot` holds the limit
in CPUs, the total periods and throttled periods, and the periods and throttled
periods of the window.

### The `CPU Limit` line

It follows `System Processors`:

```text
     System Processors: 1 aarch64
             CPU Limit: 1.00 CPUs; throttled in 21 of 42 periods; 1,203 of 86,400 since the container started <<< CPU Throttled
```

A dump that is not sampled prints only the totals:
`1.00 CPUs; throttled in 1,203 of 86,400 periods since the container started`.

### The `CPU Throttled` check

It runs after `CPU Saturated` and applies when the container has a limit. Either
measure flags, and the finding prints each one that did, as `GC Overhead` does:

The kernel counts a period only when the container had work to run in it, so a
ratio of the two counters would flag a mostly idle container that was throttled
in its few busy periods. Both measures count each throttled period as a whole
period of elapsed time instead:

- **Window:** the throttled periods add up to 25% or more of the window, and
  number at least 2. The percentage stops at 100%, because a period that
  straddles an edge of the window counts whole.
- **Lifetime:** the throttled periods add up to 10% or more of JVM uptime, once
  the JVM has run for 5 minutes, because startup work throttles a young JVM. It
  runs with or without a window.

```text
         CPU Throttled: the kernel throttled this container in 21 periods (51%) of the last 4.1s
                        the limit is 1.00 CPUs
```

The lifetime line reads
`the kernel has throttled this container for 12% of its uptime`. The finding
marks the `CPU Limit` line.

The thresholds are my calls. They follow `GC Overhead`: 25% of a window, 10% of
a lifetime, and a 5-minute wait.

### README

- Add the check to the checks table and to the list of checks that skip
  themselves.
- Say "ten checks" where the README counts them, and that a dump outside a
  limited container runs nine.
- Add `CPU Limit` to the Glossary, and a paragraph on throttling to the CPU
  caveats in "The sampling window".

## Left to the builder

- The names of the new method, fields, and classes.
- Whether the parsing sits in `JvmSnapshot` or in a small class of its own.
