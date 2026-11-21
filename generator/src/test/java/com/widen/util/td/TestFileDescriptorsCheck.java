package com.widen.util.td;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

public class TestFileDescriptorsCheck
{
    @ParameterizedTest
    @CsvSource({
            "58983, '58,983 of 65,536 file descriptors are open (90%)'",
            "65536, '65,536 of 65,536 file descriptors are open (100%)'",
            "58982," })
    void flagsOpenDescriptorsAtNinetyPercent(long open, String expectedLine) {
        FakeJvm jvm = new FakeJvm();
        jvm.unixOperatingSystem(open, 65_536);

        List<Finding> findings = new FileDescriptorsCheck().findings(jvm.snapshot());

        if (expectedLine == null) {
            assertThat(findings).isEmpty();
        }
        else {
            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).title).isEqualTo("File Descriptors");
            assertThat(findings.get(0).lines).containsExactly(expectedLine);
            assertThat(findings.get(0).marks).containsExactly("line:File Descriptors");
        }
    }

    @Test
    void doesNotFlagAFewOpenDescriptorsUnderAnUnlimitedLimit() {
        FakeJvm jvm = new FakeJvm();
        jvm.unixOperatingSystem(62, Long.MAX_VALUE);

        assertThat(new FileDescriptorsCheck().findings(jvm.snapshot())).isEmpty();
    }

    @Test
    void doesNotApplyWithoutTheUnixBean() {
        assertThat(new FileDescriptorsCheck().applies(new FakeJvm().snapshot())).isFalse();
    }

    @Test
    void appliesWithTheUnixBean() {
        FakeJvm jvm = new FakeJvm();
        jvm.unixOperatingSystem(412, 65_536);

        assertThat(new FileDescriptorsCheck().applies(jvm.snapshot())).isTrue();
    }
}
