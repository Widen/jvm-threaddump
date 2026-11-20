package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Flags a thread that holds a lock many other threads wait for, directly or through other waiters. Threads in or
 * behind a deadlock belong to {@link DeadlockCheck}.
 */
final class LockPileUpCheck implements Check
{
    static final String TITLE = "Lock Pile-up";
    static final int MIN_WAITERS = 5;
    private static final int FRAMES_SHOWN = 3;

    @Override
    public boolean applies(JvmSnapshot snapshot) {
        return true;
    }

    @Override
    public List<Finding> findings(JvmSnapshot snapshot) {
        List<Map.Entry<Long, List<ThreadSnapshot>>> piles = new ArrayList<>();
        for (Map.Entry<Long, List<ThreadSnapshot>> pile : snapshot.locks().waitersByHolder().entrySet()) {
            if (pile.getValue().size() >= MIN_WAITERS) {
                piles.add(pile);
            }
        }
        piles.sort((one, other) -> {
            int bySize = Integer.compare(other.getValue().size(), one.getValue().size());
            return bySize != 0 ? bySize
                    : Integer.compare(snapshot.position(one.getKey()), snapshot.position(other.getKey()));
        });

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<Long, List<ThreadSnapshot>> pile : piles) {
            findings.add(finding(snapshot.thread(pile.getKey()), pile.getKey(), pile.getValue().size()));
        }
        return findings;
    }

    private static Finding finding(ThreadSnapshot holder, long holderId, int waiterCount) {
        if (holder == null) {
            return new Finding(TITLE, Collections.singletonList(waiterCount
                    + " threads wait on a lock held by thread 0x" + Long.toHexString(holderId)
                    + ", which is not in the dump"), Collections.<String>emptyList());
        }
        List<String> lines = new ArrayList<>();
        lines.add(waiterCount + " threads wait on a lock held by \"" + holder.name + "\" (0x"
                + Long.toHexString(holderId) + ", " + holder.state() + ")");
        List<StackTraceElement> frames = StackFilter.kept(holder.stack());
        for (StackTraceElement frame : frames.subList(0, Math.min(FRAMES_SHOWN, frames.size()))) {
            lines.add(ThreadSection.formatFrame(frame));
        }
        return new Finding(TITLE, lines, Collections.singletonList(Finding.threadMark(holderId)));
    }
}
