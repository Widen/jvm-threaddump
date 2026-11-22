package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

interface Line
{
    int PADDING = 22;

    String toString();

    static String render(List<? extends Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line line : lines) {
            sb.append(line.toString());
        }
        return sb.toString();
    }

    class BlankLine implements Line
    {
        @Override
        public String toString() {
            return String.format("%n");
        }
    }

    class SimpleLine implements Line
    {
        SimpleLine(String value) {
            this.value = value;
        }

        private String value;

        @Override
        public String toString() {
            return String.format("%s%n", value  );
        }
    }

    class TitledLine implements Line
    {
        private String title;
        private String value;

        TitledLine(String title, String value) {
            this.title = title;
            this.value = value;
        }

        @Override
        public String toString() {
            return String.format("%" + PADDING + "s: %s%n", title, value);
        }
    }

    class MultiLine implements Line
    {
        private String title;
        private List<String> values;

        MultiLine(String title, Collection<String> values) {
            this.title = title;
            this.values = new ArrayList<>(values);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%" + PADDING + "s: %s%n", title, values.stream().findFirst().orElse("")));
            if (values.size() > 1) {
                List<String> remaining = values.subList(1, values.size());
                remaining.forEach(s -> sb.append(String.format("%" + PADDING + "s  %s%n", "", s)));
            }
            return sb.toString();
        }
    }

}
