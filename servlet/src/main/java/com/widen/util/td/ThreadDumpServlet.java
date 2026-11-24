package com.widen.util.td;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Serves a {@link JvmThreadDump} on GET as UTF-8 {@code text/plain} with headers that prevent caching.
 * A subclass customizes the dump by passing a configured {@link JvmThreadDump} to the constructor.
 * The optional request parameter {@code window} (whole seconds from 0 to 30) sets the sampling window,
 * {@code frames=all} prints every frame, and {@code group} ({@code same-stack}, {@code neighbors}, or {@code none})
 * sets the {@link ThreadGrouping}. A bad value gets status 400 and a plain-text explanation.
 */
public class ThreadDumpServlet extends HttpServlet
{
    private static final int MAX_WINDOW_SECONDS = 30;
    private static final Pattern WINDOW_SECONDS = Pattern.compile("\\d{1,2}");

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
        JvmThreadDump requested;
        try {
            requested = forRequest(dump, req.getParameter("window"), req.getParameter("frames"),
                    req.getParameter("group"));
        } catch (IllegalArgumentException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            respond(resp, e.getMessage());
            return;
        }
        respond(resp, requested.generate());
    }

    private static void respond(HttpServletResponse resp, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);

        resp.setContentType("text/plain; charset=UTF-8");
        resp.setContentLength(body.length);
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0");
        resp.setHeader("Expires", "Thu, 01 Jan 1970 12:00:00 +0000");
        resp.setHeader("X-Accel-Expires", "off");
        resp.setHeader("X-Robots-Tag", "noindex, nofollow");

        resp.getOutputStream().write(body);
    }

    /**
     * Returns {@code dump} itself when all three parameters are null, else a copy with the requested settings.
     *
     * @param window the sampling window as whole seconds from 0 to 30, or null to keep the dump's own
     * @param frames {@code all} to print every frame, or null to keep the dump's own
     * @param group {@code same-stack}, {@code neighbors}, or {@code none}, or null to keep the dump's own grouping
     * @throws IllegalArgumentException with the response text when a value is not allowed
     */
    static JvmThreadDump forRequest(JvmThreadDump dump, String window, String frames, String group) {
        if (window == null && frames == null && group == null) {
            return dump;
        }
        JvmThreadDump.Builder builder = dump.toBuilder();
        if (window != null) {
            builder.sampleWindow(Duration.ofSeconds(parseWindowSeconds(window)));
        }
        if (frames != null) {
            if (!frames.equals("all")) {
                throw new IllegalArgumentException("frames must be all");
            }
            builder.allFrames(true);
        }
        if (group != null) {
            builder.threadGrouping(parseGrouping(group));
        }
        return builder.build();
    }

    private static ThreadGrouping parseGrouping(String group) {
        switch (group) {
            case "same-stack":
                return ThreadGrouping.SAME_STACK;
            case "neighbors":
                return ThreadGrouping.NEIGHBORS;
            case "none":
                return ThreadGrouping.NONE;
            default:
                throw new IllegalArgumentException("group must be same-stack, neighbors, or none");
        }
    }

    private static int parseWindowSeconds(String window) {
        if (WINDOW_SECONDS.matcher(window).matches()) {
            int seconds = Integer.parseInt(window);
            if (seconds <= MAX_WINDOW_SECONDS) {
                return seconds;
            }
        }
        throw new IllegalArgumentException("window must be a whole number of seconds from 0 to " + MAX_WINDOW_SECONDS);
    }

}
