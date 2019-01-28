package com.widen.util.td;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public interface Line
{
    int PADDING = 25;

    String toString();

    class BlankLine implements Line
    {
        @Override
        public String toString() {
            return "\n";
        }
    }

    class TitledLine implements Line
    {
        private String title;
        private String value;

        public TitledLine(String title, String value) {
            this.title = title;
            this.value = value;
        }

        @Override
        public String toString() {
            return String.format("%" + Line.PADDING + "s: %s%n", title, value);
        }
    }

    class MultiLine implements Line
    {
        private String title;
        private List<String> values;

        public MultiLine(String title, Collection<String> values) {
            this.title = title;
            this.values = new ArrayList<>(values);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%" + Line.PADDING + "s: %s%n", title, values.stream().findFirst().orElse("")));
            if (values.size() > 1) {
                List<String> remaining = values.subList(1, values.size());
                remaining.forEach(s -> sb.append(String.format("%" + Line.PADDING + "s  %s%n", "", s)));
            }
            return sb.toString();
        }
    }

}
