package com.widen.util.td;

import java.util.Arrays;
import java.util.List;

final class TestText
{
    private TestText() {
    }

    /**
     * Renders the lines and splits the text at line separators. A trailing blank line is kept as an
     * empty element; the empty string after the final separator is dropped.
     */
    static List<String> render(List<? extends Line> lines) {
        String[] parts = Line.render(lines).split(System.lineSeparator(), -1);
        return Arrays.asList(parts).subList(0, parts.length - 1);
    }

    static String titled(String title, String value) {
        return String.format("%22s: %s", title, value);
    }

    static String continued(String value) {
        return String.format("%22s  %s", "", value);
    }
}
