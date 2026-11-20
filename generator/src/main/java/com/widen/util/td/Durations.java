package com.widen.util.td;

import java.util.concurrent.TimeUnit;

final class Durations
{
    private static final String NOT_AVAILABLE = "Not Available";

    private Durations() {
    }

    /**
     * Formats as {@code D days, H:mm:ss}, or {@code Not Available} for a negative duration.
     */
    static String daysHoursMinutesSeconds(long millis) {
        if (millis < 0) {
            return NOT_AVAILABLE;
        }
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        return String.format("%d days, %d:%02d:%02d", days, hours, minutes(millis), seconds(millis));
    }

    /**
     * Formats as {@code H:mm:ss.SSS} with unbounded hours, or {@code Not Available} for a negative duration.
     */
    static String hoursMinutesSecondsMillis(long millis) {
        if (millis < 0) {
            return NOT_AVAILABLE;
        }
        return String.format("%d:%02d:%02d.%03d", TimeUnit.MILLISECONDS.toHours(millis), minutes(millis),
                seconds(millis), millis % 1000);
    }

    private static long minutes(long millis) {
        return TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
    }

    private static long seconds(long millis) {
        return TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
    }
}
