package com.widen.util.td;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Serves a {@link JvmThreadDump} on GET as UTF-8 {@code text/plain} with headers that prevent caching.
 * A subclass customizes the dump by passing a configured {@link JvmThreadDump} to the constructor.
 */
public class ThreadDumpServlet extends HttpServlet
{
    private final JvmThreadDump dump;

    /**
     * Serves the platform dump from {@link JvmThreadDump#create()}.
     */
    public ThreadDumpServlet() {
        this(JvmThreadDump.create());
    }

    /**
     * Serves the output of the given dump, which is generated again on every request.
     */
    public ThreadDumpServlet(JvmThreadDump dump) {
        this.dump = Objects.requireNonNull(dump, "dump");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        byte[] body = dump.generate().getBytes(StandardCharsets.UTF_8);

        resp.setContentType("text/plain; charset=UTF-8");
        resp.setContentLength(body.length);
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0");
        resp.setHeader("Expires", "Thu, 01 Jan 1970 12:00:00 +0000");
        resp.setHeader("X-Accel-Expires", "off");
        resp.setHeader("X-Robots-Tag", "noindex, nofollow");

        resp.getOutputStream().write(body);
    }

}
