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

    private static final List<String> LIBRARY_PREFIXES = Arrays.asList(
            "org.eclipse.jetty.", "org.apache.tomcat.", "org.apache.catalina.", "io.netty.", "com.zaxxer.hikari.");

    private final List<String> libraryPrefixes;

    /**
     * @param idleLibraryPrefixes package prefixes of server and pool libraries to add to the built-in ones
     */
    StackPileUpCheck(List<String> idleLibraryPrefixes) {
        List<String> libraryPrefixes = new ArrayList<>(LIBRARY_PREFIXES);
        libraryPrefixes.addAll(idleLibraryPrefixes);
        this.libraryPrefixes = libraryPrefixes;
    }

    /** The {@code class#method} keys of the native waits a {@code RUNNABLE} thread idles in, on Java 8 and later. */
    private static final Set<String> NATIVE_WAITS = new HashSet<>(Arrays.asList(
            "sun.nio.ch.EPoll#wait",
            "sun.nio.ch.EPollArrayWrapper#epollWait",
            "sun.nio.ch.KQueue#poll",
            "sun.nio.ch.KQueueArrayWrapper#kevent0",
            "sun.nio.ch.WEPoll#wait",
            "sun.nio.ch.WindowsSelectorImpl$SubSelector#poll0",
            "sun.nio.ch.Net#poll",
            "sun.nio.ch.Net#accept",
            "sun.nio.ch.ServerSocketChannelImpl#accept0",
            "java.net.PlainSocketImpl#socketAccept",
            "io.netty.channel.epoll.Native#epollWait",
            "io.netty.channel.epoll.Native#epollWait0",
            "io.netty.channel.kqueue.Native#keventWait"));

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return true;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        Set<Long> countedElsewhere = new HashSet<>(snapshot.locks().inOrBehindCycles());
        for (Map.Entry<Long, List<ThreadSnapshot>> pile : LockPileUpCheck.piles(snapshot)) {
            for (ThreadSnapshot waiter : pile.getValue()) {
                countedElsewhere.add(waiter.id());
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
     * True when the stack is empty, when the thread is {@code RUNNABLE} and its top frame is a selector, poll, or
     * accept wait in {@link #NATIVE_WAITS} whatever the frames below it, or when the thread is {@code WAITING} or
     * {@code TIMED_WAITING} and no frame of its stack is application code. A {@code BLOCKED} thread is never idle.
     */
    boolean idle(ThreadSnapshot thread) {
        StackTraceElement[] stack = thread.stack();
        if (stack.length == 0) {
            return true;
        }
        if (thread.state() == Thread.State.RUNNABLE) {
            return NATIVE_WAITS.contains(StackFilter.callKey(stack[0]));
        }
        if (thread.state() != Thread.State.WAITING && thread.state() != Thread.State.TIMED_WAITING) {
            return false;
        }
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (!StackFilter.isJdk(className) && !StackFilter.startsWithAny(className, libraryPrefixes)) {
                return false;
            }
        }
        return true;
    }

    private static String groupKey(ThreadSnapshot thread) {
        StackTraceElement[] stack = thread.stack();
        StackTraceElement library = StackFilter.firstKeptOutsideJdk(stack);
        return StackFilter.callKey(stack[0]) + "|" + (library == null ? "" : StackFilter.callKey(library));
    }

    private static Finding finding(List<ThreadSnapshot> pile) {
        StackTraceElement[] stack = pile.get(0).stack();
        StackTraceElement library = StackFilter.firstKeptOutsideJdk(stack);

        List<String> lines = new ArrayList<>();
        lines.add(pile.size() + " threads are in the same call (" + ThreadSection.formatStateCounts(pile) + ")");
        lines.add(ThreadSection.formatFrame(stack[0]));
        if (library != null && !StackFilter.sameCall(library, stack[0])) {
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
