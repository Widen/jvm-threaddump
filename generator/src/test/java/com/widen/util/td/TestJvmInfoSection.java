package com.widen.util.td;

import java.lang.management.OperatingSystemMXBean;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.widen.util.td.TestText.continued;
import static com.widen.util.td.TestText.titled;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestJvmInfoSection
{
    @Test
    void printsJvmAndHostInfo() {
        assertThat(TestText.render(lines(new FakeJvm())))
                .containsExactly(
                        titled("Current Time", "11/20/2026 15:30:00 UTC"),
                        titled("Default Time Zone", "Coordinated Universal Time (UTC)"),
                        titled("Java Version", "1.8.0_472-b08 (Example Vendor)"),
                        titled("Java VM", "25.472-b08 (mixed mode)"),
                        titled("Java Home", "/opt/java"),
                        titled("Working Directory", "/srv/app"),
                        titled("Temp Directory", "/tmp"),
                        "",
                        titled("Main Arguments", ""),
                        titled("Default Encoding", "UTF-8"),
                        titled("JVM System Props", "file.encoding=UTF-8"),
                        "",
                        titled("Hostname", "app-1"),
                        titled("Operating System", "Linux (5.10)"),
                        titled("System Processors", "4 amd64"),
                        titled("System Memory", "1,024 MB available; 4,096 MB total"),
                        titled("File Descriptors", "Not Available"),
                        titled("CPU Load", "0.2500 system; 0.1250 jvm (0.0 to 1.0)"),
                        titled("System Uptime", "1 days, 2:03:04"),
                        titled("System Idle Time", "0 days, 3:25:45"),
                        titled("JVM Uptime", "1 days, 1:01:01"),
                        titled("JVM CPU Time", "0 days, 0:01:01"),
                        "");
    }

    @Test
    void marksTheCpuLoadLine() {
        FakeJvm jvm = new FakeJvm();
        JvmSnapshot snapshot = jvm.snapshot();
        Findings findings = StubCheck.findingsOf(snapshot, StubCheck.finding("Alpha", Finding.lineMark("CPU Load")));

        assertThat(TestText.render(new JvmInfoSection(jvm, snapshot, findings, emptyList(), emptyMap()).lines()))
                .contains(titled("CPU Load", "0.2500 system; 0.1250 jvm (0.0 to 1.0) <<< Alpha"));
    }

    @Test
    void printsFileDescriptorsAfterSystemMemory() {
        FakeJvm jvm = new FakeJvm();
        jvm.unixOperatingSystem(412, 65_536);
        JvmSnapshot snapshot = jvm.snapshot();
        Findings findings = StubCheck.findingsOf(snapshot, StubCheck.finding("Alpha", Finding.lineMark("File Descriptors")));

        assertThat(TestText.render(lines(jvm)))
                .containsSubsequence(
                        titled("System Memory", "1,024 MB available; 4,096 MB total"),
                        titled("File Descriptors", "412 open; 65,536 limit"),
                        titled("CPU Load", "0.2500 system; 0.1250 jvm (0.0 to 1.0)"));
        assertThat(TestText.render(new JvmInfoSection(jvm, snapshot, findings, emptyList(), emptyMap()).lines()))
                .contains(titled("File Descriptors", "412 open; 65,536 limit <<< Alpha"));
    }

    @Test
    void printsCustomValuesAndMainArgumentsInOrder() {
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("App", "cheese");
        custom.put("Build", 42);

        FakeJvm jvm = new FakeJvm();
        assertThat(TestText.render(new JvmInfoSection(jvm, jvm.snapshot(), Findings.NONE, Arrays.asList("--port", "8080"), custom).lines()))
                .containsSubsequence(
                        titled("Temp Directory", "/tmp"),
                        "",
                        titled("App", "cheese"),
                        titled("Build", "42"),
                        "",
                        titled("Main Arguments", "--port 8080"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "garbage", "12.5" })
    void printsNotAvailableForMissingHostData(String uptime) {
        FakeJvm jvm = new FakeJvm();
        OperatingSystemMXBean plainOs = mock(OperatingSystemMXBean.class);
        when(plainOs.getName()).thenReturn("Linux");
        when(plainOs.getVersion()).thenReturn("5.10");
        when(plainOs.getAvailableProcessors()).thenReturn(4);
        when(plainOs.getArch()).thenReturn("amd64");
        jvm.operatingSystem = plainOs;
        jvm.environment.clear();
        jvm.procUptime = uptime.isEmpty() ? Optional.empty() : Optional.of(uptime);

        assertThat(TestText.render(lines(jvm)))
                .contains(
                        titled("Hostname", "Not Available"),
                        titled("System Memory", "Not Available"),
                        titled("File Descriptors", "Not Available"),
                        titled("CPU Load", "Not Available"),
                        titled("System Uptime", "Not Available"),
                        titled("System Idle Time", "Not Available"),
                        titled("JVM CPU Time", "Not Available"));
    }

    @Test
    void printsNotAvailableForUndefinedJvmTimes() {
        FakeJvm jvm = new FakeJvm();
        when(jvm.runtime.getUptime()).thenReturn(-1L);
        when(((com.sun.management.OperatingSystemMXBean) jvm.operatingSystem).getProcessCpuTime()).thenReturn(-1L);

        assertThat(TestText.render(lines(jvm)))
                .contains(
                        titled("JVM Uptime", "Not Available"),
                        titled("JVM CPU Time", "Not Available"));
    }

    static Stream<Arguments> printsNotAvailableForUnsampledOsValues() {
        return Stream.of(
                arguments("System Memory", (Consumer<com.sun.management.OperatingSystemMXBean>) os ->
                        when(os.getFreePhysicalMemorySize()).thenReturn(-1L)),
                arguments("System Memory", (Consumer<com.sun.management.OperatingSystemMXBean>) os ->
                        when(os.getTotalPhysicalMemorySize()).thenReturn(-1L)),
                arguments("CPU Load", (Consumer<com.sun.management.OperatingSystemMXBean>) os ->
                        when(os.getSystemCpuLoad()).thenReturn(-1.0)),
                arguments("CPU Load", (Consumer<com.sun.management.OperatingSystemMXBean>) os ->
                        when(os.getProcessCpuLoad()).thenReturn(-1.0)));
    }

    @ParameterizedTest
    @MethodSource
    void printsNotAvailableForUnsampledOsValues(String title, Consumer<com.sun.management.OperatingSystemMXBean> sample) {
        FakeJvm jvm = new FakeJvm();
        sample.accept((com.sun.management.OperatingSystemMXBean) jvm.operatingSystem);

        assertThat(TestText.render(lines(jvm))).contains(titled(title, "Not Available"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "db.password=hunter2|db.password=****",
            "API_SECRET=abc|API_SECRET=****",
            "auth.Token=abc|auth.Token=****",
            "aws.credentials.file=/x|aws.credentials.file=****",
            "javax.net.ssl.keyStore=/k|javax.net.ssl.keyStore=****",
            "monkey=banana|monkey=****",
            "db.pass=hunter2|db.pass=****",
            "api.pwd=x|api.pwd=****",
            "api.key=|api.key=****",
            "token=a=b|token=****",
            "jdbc.url=jdbc:postgresql://db/app?user=app&password=x|jdbc.url=****",
            "jdbc.url=jdbc:postgresql://app:hunter2@db/prod|jdbc.url=****",
            "app.opts=retries=3;Token=abc|app.opts=****",
            "app.url=https://user@example.com/|app.url=https://user@example.com/",
            "app.url=https://example.com/?page=2|app.url=https://example.com/?page=2",
            "app.note=password|app.note=password",
            "secret.flag|secret.flag",
            "file.encoding=UTF-8|file.encoding=UTF-8" })
    void masksSecretPropertyValues(String property, String expected) {
        assertThat(JvmInfoSection.maskSecret(property)).isEqualTo(expected);
    }

    @Test
    void sortsSystemPropertiesAfterMasking() {
        FakeJvm jvm = new FakeJvm();
        when(jvm.runtime.getInputArguments())
                .thenReturn(Arrays.asList("-Dz.token=abc", "-Da=1", "-Xmx1g", "-javaagent:x.jar"));

        assertThat(TestText.render(lines(jvm)))
                .containsSubsequence(titled("JVM System Props", "a=1"), continued("z.token=****"));
    }

    private static List<Line> lines(FakeJvm jvm) {
        return new JvmInfoSection(jvm, jvm.snapshot(), Findings.NONE, emptyList(), emptyMap()).lines();
    }
}
