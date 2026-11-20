package com.widen.util.td;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flags many threads that are in the same call. Threads group by the top frame of the stack and by the first frame
 * outside the JDK, which names the library the thread is in. Idle threads, the thread generating the dump, and threads
 * that {@link DeadlockCheck} or {@link LockPileUpCheck} already count are left out.
 */
final class StackPileUpCheck implements Check
{
    static final String TITLE = "Stack Pile-up";
    static final int MIN_THREADS = 10;
    private static final int NAMES_SHOWN = 3;

    private static final List<String> IDLE_PREFIXES = Arrays.asList(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.eclipse.jetty.", "org.apache.tomcat.", "org.apache.catalina.", "io.netty.", "com.zaxxer.hikari.");

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return true;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        Set<Long> countedElsewhere = new HashSet<>(snapshot.locks().inOrBehindCycles());
        for (List<ThreadSnapshot> waiters : snapshot.locks().waitersByHolder().values()) {
            if (waiters.size() >= LockPileUpCheck.MIN_WAITERS) {
                for (ThreadSnapshot waiter : waiters) {
                    countedElsewhere.add(waiter.id());
                }
            }
        }

        Map<String, List<ThreadSnapshot>> groups = new LinkedHashMap<>();
        for (ThreadSnapshot thread : snapshot.threads) {
            if (idle(thread) || thread.id() == snapshot.generatingThreadId || countedElsewhere.contains(thread.id())) {
                continue;
            }
            groups.computeIfAbsent(groupKey(thread), key -> new ArrayList<>()).add(thread);
        }

        List<List<ThreadSnapshot>> piles = new ArrayList<>();
        for (List<ThreadSnapshot> group : groups.values()) {
            if (group.size() >= MIN_THREADS) {
                piles.add(group);
            }
        }
        piles.sort((one, other) -> Integer.compare(other.size(), one.size()));

        List<Finding> findings = new ArrayList<>();
        for (List<ThreadSnapshot> pile : piles) {
            findings.add(finding(pile));
        }
        return findings;
    }

    /**
     * True when the thread is {@code WAITING} or {@code TIMED_WAITING} and no frame of its stack is application code,
     * or when its stack is empty.
     */
    static boolean idle(ThreadSnapshot thread) {
        StackTraceElement[] stack = thread.stack();
        if (stack.length == 0) {
            return true;
        }
        if (thread.state() != Thread.State.WAITING && thread.state() != Thread.State.TIMED_WAITING) {
            return false;
        }
        for (StackTraceElement frame : stack) {
            if (!StackFilter.startsWithAny(frame.getClassName(), IDLE_PREFIXES)) {
                return false;
            }
        }
        return true;
    }

    private static String groupKey(ThreadSnapshot thread) {
        StackTraceElement[] stack = thread.stack();
        StackTraceElement library = StackFilter.firstKeptOutsideJdk(stack);
        return callKey(stack[0]) + "|" + (library == null ? "" : callKey(library));
    }

    private static String callKey(StackTraceElement frame) {
        return frame.getClassName() + "#" + frame.getMethodName();
    }

    private static Finding finding(List<ThreadSnapshot> pile) {
        StackTraceElement[] stack = pile.get(0).stack();
        StackTraceElement library = StackFilter.firstKeptOutsideJdk(stack);

        List<String> lines = new ArrayList<>();
        lines.add(pile.size() + " threads are in the same call (" + ThreadSection.formatStateCounts(pile) + ")");
        lines.add(ThreadSection.formatFrame(stack[0]));
        if (library != null && !callKey(library).equals(callKey(stack[0]))) {
            lines.add(ThreadSection.formatFrame(library));
        }

        StringBuilder names = new StringBuilder();
        for (ThreadSnapshot thread : pile.subList(0, NAMES_SHOWN)) {
            names.append('"').append(thread.name).append("\", ");
        }
        names.setLength(names.length() - 2);
        lines.add(names + " and " + (pile.size() - NAMES_SHOWN) + " more");
        return new Finding(TITLE, lines, new ArrayList<String>());
    }
}
