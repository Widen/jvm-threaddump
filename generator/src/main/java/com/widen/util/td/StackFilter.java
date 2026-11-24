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
     * Returns the top frame, which is always kept, followed by every other frame that is not hidden.
     */
    static List<StackTraceElement> kept(StackTraceElement[] stack) {
        List<StackTraceElement> kept = new ArrayList<>();
        for (int i = 0; i < stack.length; i++) {
            if (i == 0 || !hidden(stack[i])) {
                kept.add(stack[i]);
            }
        }
        return kept;
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
