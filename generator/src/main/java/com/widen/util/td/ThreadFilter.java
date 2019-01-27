package com.widen.util.td;

/**
 * Filters threads out by some criteria.  Useful for tightening the scope of results
 * when looking for specific thread activity.
 */
public interface ThreadFilter
{
    /**
     * Returns true if thread makes it through the filter and should be displayed.
     * @return boolean determining if thread is allowed to be displayed
     */
    boolean allowThread(Thread thread);

    /**
     * Describe this filter.
     * @return A String value that can be used to represent this instance of a filter
     */
    String getDescription();
}
