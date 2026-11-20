package com.widen.util.td;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestThreadDumpServlet
{
    @Test
    void servesTheDumpAsUtf8Text() throws Exception {
        ThreadDumpServlet servlet = new ThreadDumpServlet(JvmThreadDump.builder()
                .sampleWindow(Duration.ZERO)
                .customValues(Collections.singletonMap("Owner", "Zoë ✓"))
                .build());
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream written = respondInto(response);

        servlet.doGet(mock(HttpServletRequest.class), response);

        String text = new String(written.toByteArray(), StandardCharsets.UTF_8);
        assertThat(text).contains("Owner: Zoë ✓");
        verify(response).setContentType("text/plain; charset=UTF-8");
        verify(response).setContentLength(written.size());
        assertThat(written.size()).isGreaterThan(text.length());
        verify(response).setHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0");
        verify(response).setHeader("Expires", "Thu, 01 Jan 1970 12:00:00 +0000");
        verify(response).setHeader("X-Accel-Expires", "off");
        verify(response).setHeader("X-Robots-Tag", "noindex, nofollow");
        verify(response, never()).getWriter();
    }

    @Test
    void servesValuesFromASubclassLikeMicas() throws Exception {
        ThreadDumpServlet servlet = new MicaThreadDumpServlet(
                Collections.<String, Object>singletonMap("Region", "us-east-1"), "cheese", "test");

        String body = get(servlet);

        assertThat(body).contains("App: cheese", "Environment: test", "Region: us-east-1",
                "Main Arguments: --app cheese");
    }

    @Test
    void servesThePlatformDumpByDefault() throws Exception {
        assertThat(get(new ThreadDumpServlet(), "0", null)).contains("Thread Count: ");
    }

    @Test
    void buildsADumpWithTheRequestedWindowAndFrames() {
        JvmThreadDump dump = zeroWindowDump();

        JvmThreadDump windowOnly = ThreadDumpServlet.forRequest(dump, "5", null);
        assertThat(windowOnly.sampleWindow()).isEqualTo(Duration.ofSeconds(5));
        assertThat(windowOnly.allFrames()).isFalse();

        JvmThreadDump framesOnly = ThreadDumpServlet.forRequest(dump, null, "all");
        assertThat(framesOnly.allFrames()).isTrue();
        assertThat(framesOnly.sampleWindow()).isEqualTo(Duration.ZERO);

        JvmThreadDump both = ThreadDumpServlet.forRequest(dump, "0", "all");
        assertThat(both.sampleWindow()).isEqualTo(Duration.ZERO);
        assertThat(both.allFrames()).isTrue();

        assertThat(ThreadDumpServlet.forRequest(dump, null, null)).isSameAs(dump);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "31", "1.5", "abc", "", "+5", "99999999999"})
    void rejectsABadWindow(String window) {
        assertThatThrownBy(() -> ThreadDumpServlet.forRequest(zeroWindowDump(), window, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("window must be a whole number of seconds from 0 to 30");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "30", "07"})
    void acceptsWindowsFromZeroToThirty(String window) {
        JvmThreadDump dump = ThreadDumpServlet.forRequest(zeroWindowDump(), window, null);

        assertThat(dump.sampleWindow()).isEqualTo(Duration.ofSeconds(Long.parseLong(window)));
    }

    @Test
    void rejectsFramesOtherThanAll() {
        assertThatThrownBy(() -> ThreadDumpServlet.forRequest(zeroWindowDump(), null, "some"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("frames must be all");
    }

    @Test
    void servesTheRequestedDump() throws Exception {
        ThreadDumpServlet servlet = new ThreadDumpServlet(JvmThreadDump.builder().build());

        assertThat(get(servlet, "0", "all")).contains("not sampled");
    }

    @Test
    void answers400WithTheMessageForABadParameter() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream written = respondInto(response);

        new ThreadDumpServlet(zeroWindowDump()).doGet(requestWith("31", null), response);

        verify(response).setStatus(400);
        verify(response).setContentType("text/plain; charset=UTF-8");
        assertThat(new String(written.toByteArray(), StandardCharsets.UTF_8))
                .isEqualTo("window must be a whole number of seconds from 0 to 30");
        verify(response).setContentLength(written.size());
        verify(response).setHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0");
        verify(response).setHeader("Expires", "Thu, 01 Jan 1970 12:00:00 +0000");
        verify(response).setHeader("X-Accel-Expires", "off");
        verify(response).setHeader("X-Robots-Tag", "noindex, nofollow");
    }

    private static JvmThreadDump zeroWindowDump() {
        return JvmThreadDump.builder().sampleWindow(Duration.ZERO).build();
    }

    private static HttpServletRequest requestWith(String window, String frames) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("window")).thenReturn(window);
        when(request.getParameter("frames")).thenReturn(frames);
        return request;
    }

    private static String get(ThreadDumpServlet servlet) throws Exception {
        return get(servlet, null, null);
    }

    private static String get(ThreadDumpServlet servlet, String window, String frames) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream written = respondInto(response);

        servlet.doGet(requestWith(window, frames), response);

        return new String(written.toByteArray(), StandardCharsets.UTF_8);
    }

    private static ByteArrayOutputStream respondInto(HttpServletResponse response) throws IOException {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new ServletOutputStream()
        {
            @Override
            public void write(int b) {
                written.write(b);
            }
        });
        return written;
    }

    static class MicaThreadDumpServlet extends ThreadDumpServlet
    {
        MicaThreadDumpServlet(Map<String, Object> contribution, String appName, String env) {
            super(JvmThreadDump.builder()
                    .sampleWindow(Duration.ZERO)
                    .mainArguments(Arrays.asList("--app", appName))
                    .customValues(values(contribution, appName, env))
                    .build());
        }

        private static Map<String, Object> values(Map<String, Object> contribution, String appName, String env) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("App", appName);
            values.put("Environment", env);
            values.putAll(contribution);
            return values;
        }
    }
}
