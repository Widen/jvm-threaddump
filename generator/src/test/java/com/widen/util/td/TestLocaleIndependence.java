package com.widen.util.td;

import java.lang.management.MemoryUsage;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.widen.util.td.FakeJvm.MB;
import static com.widen.util.td.TestText.titled;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

public class TestLocaleIndependence
{
    private final Locale originalLocale = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    void formatsNumbersTheSameInAnyDefaultLocale() {
        Locale.setDefault(Locale.GERMANY);

        assertThat(MemorySection.formatUsage(new MemoryUsage(256 * MB, 64 * MB, 128 * MB, 256 * MB)))
                .startsWith("50%/25%");
        FakeJvm jvm = new FakeJvm();
        assertThat(TestText.render(new JvmInfoSection(jvm, jvm.snapshot(), Findings.NONE, emptyList(), emptyMap()).lines()))
                .contains(titled("CPU Load", "0.2500 system; 0.1250 jvm (0.0 to 1.0)"));
    }
}
