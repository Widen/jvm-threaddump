package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who waits on whom for a lock. A thread <em>waits on an owner</em> when its {@code ThreadInfo} names another thread
 * as the lock's owner and the thread is {@code BLOCKED}, or is {@code WAITING} or {@code TIMED_WAITING} with a top
 * frame outside {@code java.lang.Object}. A thread inside {@code Object.wait()} reports the monitor's holder as its
 * owner too, but it waits for a notify, not for the lock.
 */
final class LockGraph
{
    private final List<List<ThreadSnapshot>> cycles;
    private final List<List<ThreadSnapshot>> behindCycles;
    private final Set<Long> inOrBehindCycles;
    private final Map<Long, List<ThreadSnapshot>> waitersByHolder;
    private final List<List<ThreadSnapshot>> deadlockCycles;
    private final List<Long> deadlockedIds;

    private LockGraph(List<List<ThreadSnapshot>> cycles, List<List<ThreadSnapshot>> behindCycles,
            Set<Long> inOrBehindCycles, Map<Long, List<ThreadSnapshot>> waitersByHolder, long[] jvmDeadlockedIds) {
        this.cycles = cycles;
        this.behindCycles = behindCycles;
        this.inOrBehindCycles = inOrBehindCycles;
        this.waitersByHolder = waitersByHolder;

        boolean trustCycles = jvmDeadlockedIds.length > 0 && !cycles.isEmpty();
        this.deadlockCycles = trustCycles ? cycles : Collections.<List<ThreadSnapshot>>emptyList();
        List<Long> deadlockedIds = new ArrayList<>();
        if (trustCycles) {
            for (List<ThreadSnapshot> cycle : cycles) {
                for (ThreadSnapshot thread : cycle) {
                    deadlockedIds.add(thread.id());
                }
            }
        } else {
            for (long id : jvmDeadlockedIds) {
                deadlockedIds.add(id);
            }
        }
        this.deadlockedIds = Collections.unmodifiableList(deadlockedIds);
    }

    /**
     * @param threads in dump order
     * @param jvmDeadlockedIds the ids the JVM reports as deadlocked, empty when it reports none
     */
    static LockGraph of(List<ThreadSnapshot> threads, long[] jvmDeadlockedIds) {
        Map<Long, ThreadSnapshot> byId = new HashMap<>();
        for (ThreadSnapshot thread : threads) {
            byId.put(thread.id(), thread);
        }

        List<List<ThreadSnapshot>> cycles = new ArrayList<>();
        Map<Long, Integer> cycleOf = new HashMap<>();
        for (ThreadSnapshot start : threads) {
            if (!cycleOf.containsKey(start.id())) {
                List<ThreadSnapshot> cycle = cycleThrough(start, byId);
                if (cycle != null) {
                    for (ThreadSnapshot member : cycle) {
                        cycleOf.put(member.id(), cycles.size());
                    }
                    cycles.add(Collections.unmodifiableList(cycle));
                }
            }
        }

        List<List<ThreadSnapshot>> behindCycles = new ArrayList<>();
        for (int i = 0; i < cycles.size(); i++) {
            behindCycles.add(new ArrayList<>());
        }
        Set<Long> inOrBehindCycles = new HashSet<>(cycleOf.keySet());
        Map<Long, List<ThreadSnapshot>> waitersByHolder = new LinkedHashMap<>();
        for (ThreadSnapshot thread : threads) {
            if (cycleOf.containsKey(thread.id()) || waitedOwnerId(thread) == -1) {
                continue;
            }
            ThreadSnapshot current = thread;
            while (true) {
                long ownerId = waitedOwnerId(current);
                if (ownerId == -1) {
                    waitersByHolder.computeIfAbsent(current.id(), id -> new ArrayList<>()).add(thread);
                    break;
                }
                ThreadSnapshot owner = byId.get(ownerId);
                if (owner == null) {
                    waitersByHolder.computeIfAbsent(ownerId, id -> new ArrayList<>()).add(thread);
                    break;
                }
                Integer cycleIndex = cycleOf.get(owner.id());
                if (cycleIndex != null) {
                    behindCycles.get(cycleIndex).add(thread);
                    inOrBehindCycles.add(thread.id());
                    break;
                }
                current = owner;
            }
        }
        waitersByHolder.replaceAll((id, waiters) -> Collections.unmodifiableList(waiters));
        return new LockGraph(Collections.unmodifiableList(cycles), behindCycles,
                Collections.unmodifiableSet(inOrBehindCycles), Collections.unmodifiableMap(waitersByHolder),
                jvmDeadlockedIds);
    }

    /**
     * The threads in {@code start}'s cycle, starting with {@code start} and following owners, or null when following
     * owners from {@code start} never leads back to it.
     */
    private static List<ThreadSnapshot> cycleThrough(ThreadSnapshot start, Map<Long, ThreadSnapshot> byId) {
        List<ThreadSnapshot> path = new ArrayList<>();
        path.add(start);
        Set<Long> seen = new HashSet<>();
        seen.add(start.id());
        ThreadSnapshot current = start;
        while (true) {
            long ownerId = waitedOwnerId(current);
            ThreadSnapshot owner = ownerId == -1 ? null : byId.get(ownerId);
            if (owner == null) {
                return null;
            }
            if (owner == start) {
                return path;
            }
            if (!seen.add(owner.id())) {
                return null;
            }
            path.add(owner);
            current = owner;
        }
    }

    /** The owner's id, or -1 when the thread waits on no owner. */
    private static long waitedOwnerId(ThreadSnapshot thread) {
        long ownerId = thread.info.getLockOwnerId();
        if (ownerId == -1 || ownerId == thread.id()) {
            return -1;
        }
        switch (thread.state()) {
            case BLOCKED:
                return ownerId;
            case WAITING:
            case TIMED_WAITING:
                StackTraceElement[] stack = thread.stack();
                return stack.length > 0 && !"java.lang.Object".equals(stack[0].getClassName()) ? ownerId : -1;
            default:
                return -1;
        }
    }

    /**
     * Each cycle starts at its first thread in dump order and follows owners. The cycles are in dump order of their
     * first threads.
     */
    List<List<ThreadSnapshot>> cycles() {
        return cycles;
    }

    /**
     * The cycles to report as deadlocks: {@link #cycles()} when the JVM reports deadlocked threads, and none otherwise.
     * Empty also when the JVM reports deadlocked threads but the owner graph holds no cycle.
     */
    List<List<ThreadSnapshot>> deadlockCycles() {
        return deadlockCycles;
    }

    /**
     * The ids of the threads in {@link #deadlockCycles()}, cycle by cycle, or the ids the JVM reports as deadlocked
     * when that list is empty. Empty when the JVM reports no deadlocked threads. An id can be missing from the
     * snapshot's threads.
     */
    List<Long> deadlockedIds() {
        return deadlockedIds;
    }

    /** The ids of the threads in a cycle and of the threads that wait, directly or not, on one. */
    Set<Long> inOrBehindCycles() {
        return inOrBehindCycles;
    }

    /**
     * The threads outside {@code cycle} that wait, directly or through other waiters, on one of its threads, in dump
     * order.
     *
     * @param cycle one of {@link #cycles()}
     */
    List<ThreadSnapshot> behind(List<ThreadSnapshot> cycle) {
        return Collections.unmodifiableList(behindCycles.get(cycles.indexOf(cycle)));
    }

    /**
     * Maps the id of each holder, the thread at the end of a chain of owners, to every thread behind it, in dump
     * order. A holder waits on no owner, or is missing from the snapshot. Threads in or behind a cycle are left out.
     */
    Map<Long, List<ThreadSnapshot>> waitersByHolder() {
        return waitersByHolder;
    }
}
