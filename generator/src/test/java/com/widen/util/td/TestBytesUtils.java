package com.widen.util.td;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBytesUtils
{

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|0 bytes", "1023|1,023 bytes", "1024|1 KB", "1048575|1,023 KB",
            "1048576|1 MB", "4294967296|4,096 MB", "-1|-1 bytes"})
    void formatsByteCountsAtUnitBoundaries(long bytes, String expected) {
        assertThat(BytesUtils.byteCountToDisplaySize(bytes)).isEqualTo(expected);
    }

}
