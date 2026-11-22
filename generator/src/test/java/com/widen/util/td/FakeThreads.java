package com.widen.util.td;

import java.lang.management.ThreadInfo;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds unstarted threads with a fixed id and the matching {@link ThreadInfo} snapshots.
 */
final class FakeThreads
{
    static final ThreadGroup GROUP = new ThreadGroup("test-group");

    private FakeThreads() {
    }

    static Thread thread(String name, long id) {
        return thread(name, id, null);
    }

    /**
     * A thread whose {@code toString()} returns the given text, or the standard {@code Thread.toString()} when null.
     * An unstarted thread inherits daemon status and priority from its creator, so both are set explicitly.
     */
    static Thread thread(String name, long id, String toString) {
        Thread thread = new Thread(GROUP, name)
        {
            @Override
            public long getId() {
                return id;
            }

            @Override
            public String toString() {
                return toString == null ? super.toString() : toString;
            }
        };
        thread.setDaemon(false);
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    }

    static StackTraceElement frame(String className, String method) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        return new StackTraceElement(className, method, simpleName + ".java", 1);
    }

    static ThreadInfo info(long id, Thread.State state, StackTraceElement... frames) {
        ThreadInfo info = mock(ThreadInfo.class);
        when(info.getThreadId()).thenReturn(id);
        when(info.getThreadState()).thenReturn(state);
        when(info.getStackTrace()).thenReturn(frames);
        when(info.getLockName()).thenReturn(null);
        when(info.getLockOwnerId()).thenReturn(-1L);
        when(info.getBlockedCount()).thenReturn(0L);
        when(info.getWaitedCount()).thenReturn(0L);
        return info;
    }
}
