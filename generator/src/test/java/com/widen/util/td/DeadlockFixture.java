package com.widen.util.td;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deadlocks two daemon threads on two {@link ReentrantLock}s until {@link #close()} interrupts them.
 * Unlike a monitor deadlock, the lock waits are interruptible, so the test JVM is clean afterward.
 */
final class DeadlockFixture implements AutoCloseable
{
    private static final long TIMEOUT_MILLIS = 5000;
    private static final long POLL_MILLIS = 10;

    private final Thread threadOne;
    private final Thread threadTwo;

    private DeadlockFixture(Thread threadOne, Thread threadTwo)
    {
        this.threadOne = threadOne;
        this.threadTwo = threadTwo;
    }

    /**
     * Starts the threads and returns once the JVM reports both as deadlocked.
     *
     * @throws IllegalStateException if the deadlock does not form within 5 seconds, or the caller is interrupted
     */
    static DeadlockFixture start()
    {
        ReentrantLock lockA = new ReentrantLock();
        ReentrantLock lockB = new ReentrantLock();
        CountDownLatch bothHoldFirstLock = new CountDownLatch(2);

        DeadlockFixture fixture = new DeadlockFixture(
            newThread("deadlock-fixture-1", lockA, lockB, bothHoldFirstLock),
            newThread("deadlock-fixture-2", lockB, lockA, bothHoldFirstLock));
        fixture.threadOne.start();
        fixture.threadTwo.start();

        try
        {
            fixture.awaitDeadlock();
        }
        catch (RuntimeException e)
        {
            fixture.close();
            throw e;
        }
        return fixture;
    }

    long[] threadIds()
    {
        return new long[] {threadOne.getId(), threadTwo.getId()};
    }

    /**
     * Interrupts both threads, which release their locks and exit, and waits for them to finish.
     * Joins even when the caller's interrupt flag is set, and restores the flag afterward.
     *
     * @throws IllegalStateException if a thread is still alive 5 seconds after its interrupt
     */
    @Override
    public void close()
    {
        threadOne.interrupt();
        threadTwo.interrupt();
        boolean callerInterrupted = Thread.interrupted();
        try
        {
            callerInterrupted |= join(threadOne);
            callerInterrupted |= join(threadTwo);
        }
        finally
        {
            if (callerInterrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
        requireExited(threadOne);
        requireExited(threadTwo);
    }

    private void awaitDeadlock()
    {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        while (!isDeadlocked(threadBean.findDeadlockedThreads()))
        {
            if (System.nanoTime() - deadline > 0)
            {
                throw new IllegalStateException("Threads did not deadlock within " + TIMEOUT_MILLIS + " ms");
            }
            sleepPollInterval();
        }
    }

    private boolean isDeadlocked(long[] deadlockedIds)
    {
        if (deadlockedIds == null)
        {
            return false;
        }
        boolean one = false;
        boolean two = false;
        for (long id : deadlockedIds)
        {
            one |= id == threadOne.getId();
            two |= id == threadTwo.getId();
        }
        return one && two;
    }

    private static void sleepPollInterval()
    {
        try
        {
            Thread.sleep(POLL_MILLIS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the deadlock", e);
        }
    }

    /** Waits up to the timeout for the thread to exit, retrying after an interrupt; returns whether one occurred. */
    private static boolean join(Thread thread)
    {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        long remainingMillis = TIMEOUT_MILLIS;
        while (thread.isAlive() && remainingMillis > 0)
        {
            try
            {
                thread.join(remainingMillis);
            }
            catch (InterruptedException e)
            {
                interrupted = true;
            }
            remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        }
        return interrupted;
    }

    private static void requireExited(Thread thread)
    {
        if (thread.isAlive())
        {
            throw new IllegalStateException(thread.getName() + " did not exit within " + TIMEOUT_MILLIS + " ms");
        }
    }

    private static Thread newThread(String name, ReentrantLock first, ReentrantLock second, CountDownLatch bothHoldFirstLock)
    {
        Thread thread = new Thread(() -> {
            try
            {
                first.lockInterruptibly();
                try
                {
                    bothHoldFirstLock.countDown();
                    bothHoldFirstLock.await();
                    second.lockInterruptibly();
                    second.unlock();
                }
                finally
                {
                    first.unlock();
                }
            }
            catch (InterruptedException e)
            {
                // close() interrupted this thread; the locks are released and the thread exits.
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }
}
