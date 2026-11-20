package com.widen.util.td;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decides which stack frames are plumbing: reflection, generated proxies, and the Tapestry and servlet container
 * layers that sit between the application's own calls.
 */
final class StackFilter
{
    private static final List<String> JDK_PREFIXES = Arrays.asList("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private static final List<String> HIDDEN_PREFIXES = Arrays.asList(
            "sun.reflect.", "jdk.internal.reflect.", "java.lang.reflect.", "java.lang.invoke.",
            "com.sun.proxy.", "jdk.proxy",
            "org.apache.tapestry5.",
            "org.eclipse.jetty.", "org.glassfish.jersey.", "org.glassfish.hk2.", "org.jvnet.hk2.", "javax.servlet.");

    private StackFilter() {
    }

    static boolean isJdk(String className) {
        return startsWithAny(className, JDK_PREFIXES);
    }

    static boolean hidden(StackTraceElement frame) {
        String className = frame.getClassName();
        return startsWithAny(className, HIDDEN_PREFIXES)
                || (className.indexOf('.') < 0 && className.startsWith("$"))
                || className.contains("$$");
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
