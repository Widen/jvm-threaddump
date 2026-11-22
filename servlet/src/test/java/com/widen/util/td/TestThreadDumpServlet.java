package com.widen.util.td;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestThreadDumpServlet
{
    @Test
    void servesTheDumpAsUtf8Text() throws Exception {
        ThreadDumpServlet servlet = new ThreadDumpServlet(JvmThreadDump.builder()
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
        assertThat(get(new ThreadDumpServlet())).contains("Thread Count: ");
    }

    private static String get(ThreadDumpServlet servlet) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ByteArrayOutputStream written = respondInto(response);

        servlet.doGet(mock(HttpServletRequest.class), response);

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
