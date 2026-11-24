package com.widen.util.td;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which stack frames are plumbing: reflection, generated proxies, the Tapestry and servlet container layers
 * that sit between the application's own calls, executor and lock plumbing, Netty's thread wrappers, and accessors.
 */
final class StackFilter
{
    private static final List<String> JDK_PREFIXES = Arrays.asList("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private static final List<String> HIDDEN_PREFIXES = Arrays.asList(
            "sun.reflect.", "jdk.internal.reflect.", "java.lang.reflect.", "java.lang.invoke.",
            "com.sun.proxy.", "jdk.proxy",
            "org.apache.tapestry5.",
            "org.eclipse.jetty.", "org.glassfish.jersey.", "org.glassfish.hk2.", "org.jvnet.hk2.",
            // Not all of org.apache.tomcat., which would hide the Tomcat JDBC pool a thread waits in.
            "org.apache.catalina.", "org.apache.coyote.", "org.apache.tomcat.util.", "org.apache.tomcat.websocket.",
            "javax.servlet.", "jakarta.servlet.",
            "java.util.concurrent.locks.LockSupport", "java.util.concurrent.locks.AbstractQueuedSynchronizer",
            "java.util.concurrent.locks.AbstractQueuedLongSynchronizer",
            "io.netty.util.concurrent.SingleThreadEventExecutor$", "io.netty.util.internal.ThreadExecutorMap$",
            "io.netty.util.concurrent.FastThreadLocalRunnable");

    /** The {@code class#method} keys of the executor frames every pool thread or submitted task passes through. */
    private static final Set<String> HIDDEN_METHODS = new HashSet<>(Arrays.asList(
            "java.util.concurrent.ThreadPoolExecutor#runWorker",
            "java.util.concurrent.ThreadPoolExecutor$Worker#run",
            "java.util.concurrent.FutureTask#run",
            "java.util.concurrent.FutureTask#runAndReset",
            "java.util.concurrent.Executors$RunnableAdapter#call",
            "java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask#run",
            "java.util.concurrent.ForkJoinWorkerThread#run",
            "java.util.concurrent.ForkJoinPool#runWorker",
            "java.util.concurrent.ForkJoinPool#managedBlock",
            "java.util.concurrent.ForkJoinPool#unmanagedBlock",
            "java.lang.Thread#run",
            "java.lang.Thread#runWith"));

    private static final Pattern ACCESSOR = Pattern.compile("access\\$\\d+");

    /** The client and I/O libraries whose runs of frames collapse to their innermost and outermost frame. */
    private static final List<String> COLLAPSED_PREFIXES = Arrays.asList(
            "software.amazon.awssdk.", "com.amazonaws.",
            "org.apache.hc.", "org.apache.http.", "okhttp3.", "okio.", "io.netty.",
            "sun.nio.ch.", "sun.security.ssl.", "java.net.");

    private StackFilter() {
    }

    static boolean isJdk(String className) {
        return startsWithAny(className, JDK_PREFIXES);
    }

    static boolean hidden(StackTraceElement frame) {
        String className = frame.getClassName();
        return startsWithAny(className, HIDDEN_PREFIXES)
                || (className.indexOf('.') < 0 && className.startsWith("$"))
                || className.contains("$$")
                || HIDDEN_METHODS.contains(className + "#" + frame.getMethodName())
                || ACCESSOR.matcher(frame.getMethodName()).matches();
    }

    /**
     * Returns the top frame, which is always kept, followed by every other frame that is not hidden and does not
     * repeat the class and method of the frame kept before it. Of each run of two or more of those frames from one
     * collapsed library, only the first and the last are kept, or only the first when both are the same call.
     */
    static List<StackTraceElement> kept(StackTraceElement[] stack) {
        List<StackTraceElement> calls = new ArrayList<>();
        for (int i = 0; i < stack.length; i++) {
            if (i == 0 || !hidden(stack[i]) && !sameCall(stack[i], calls.get(calls.size() - 1))) {
                calls.add(stack[i]);
            }
        }

        List<StackTraceElement> kept = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            String library = collapsedPrefix(calls.get(i));
            boolean insideRun = library != null && i > 0 && i < calls.size() - 1
                    && library.equals(collapsedPrefix(calls.get(i - 1)))
                    && library.equals(collapsedPrefix(calls.get(i + 1)));
            // A run's two ends can be the same call, such as a library calling back into itself.
            if (!insideRun && (i == 0 || !sameCall(calls.get(i), kept.get(kept.size() - 1)))) {
                kept.add(calls.get(i));
            }
        }
        return kept;
    }

    private static boolean sameCall(StackTraceElement frame, StackTraceElement other) {
        return frame.getClassName().equals(other.getClassName()) && frame.getMethodName().equals(other.getMethodName());
    }

    private static String collapsedPrefix(StackTraceElement frame) {
        for (String prefix : COLLAPSED_PREFIXES) {
            if (frame.getClassName().startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /**
     * Returns the first kept frame whose class is outside the JDK, or null when there is none.
     */
    static StackTraceElement firstKeptOutsideJdk(StackTraceElement[] stack) {
        for (StackTraceElement frame : kept(stack)) {
            if (!isJdk(frame.getClassName())) {
                return frame;
            }
        }
        return null;
    }

    static boolean startsWithAny(String className, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
