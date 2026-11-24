package com.widen.util.td;

/**
 * How the thread list prints threads whose stacks are equal: the same frames in the same order, line numbers
 * included. A group prints every member's header, in name order, then the shared stack once. A thread with an empty
 * stack always prints alone.
 */
public enum ThreadGrouping
{
    /**
     * Every thread whose stack equals another's prints under the first of them in name order, and the group sits
     * where that first thread would. The default.
     */
    SAME_STACK,

    /**
     * Only threads next to each other in name order share a stack.
     */
    NEIGHBORS,

    /**
     * Every thread prints its own stack.
     */
    NONE
}
