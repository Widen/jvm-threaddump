package com.widen.util.td;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ThreadDumpServlet extends HttpServlet
{

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        JvmThreadDump dump = new JvmThreadDump();
        String out = dump.generate();

        resp.setContentType("text/plain");
        resp.setContentLength(out.length());
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0");
        resp.setHeader("Expires", "Thu, 01 Jan 1970 12:00:00 +0000");
        resp.setHeader("X-Accel-Expires", "off");
        resp.setHeader("X-Robots-Tag", "noindex, nofollow");

        PrintWriter writer = resp.getWriter();
        writer.write(out);
    }

}
