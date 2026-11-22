package com.widen.util.td;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

public class TestDurations
{

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|0 days, 0:00:00", "90061000|1 days, 1:01:01",
            "345599000|3 days, 23:59:59", "-1|Not Available"})
    void formatsUptimes(long millis, String expected) {
        assertThat(Durations.daysHoursMinutesSeconds(millis)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|0:00:00.000", "1234|0:00:01.234", "90061001|25:01:01.001", "-1|Not Available"})
    void formatsCollectionTimesWithMillis(long millis, String expected) {
        assertThat(Durations.hoursMinutesSecondsMillis(millis)).isEqualTo(expected);
    }

}
