package com.widen.util.td;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestLine
{

    @Test
    void padsTitlesToTwentyTwoColumns() {
        assertThat(new Line.TitledLine("Heap", "x").toString())
                .isEqualTo("                  Heap: x" + System.lineSeparator());
        assertThat(new Line.TitledLine("Compressed Class Space*", "x").toString())
                .isEqualTo("Compressed Class Space*: x" + System.lineSeparator());
    }

    @Test
    void continuesMultiLineValuesUnderTheFirst() {
        assertThat(TestText.render(Arrays.asList(new Line.MultiLine("Props", Arrays.asList("a=1", "b=2")))))
                .containsExactly("                 Props: a=1", "                        b=2");
        assertThat(TestText.render(Arrays.asList(new Line.MultiLine("Props", Collections.emptyList()))))
                .containsExactly("                 Props: ");
    }

    @Test
    void rendersSimpleAndBlankLines() {
        assertThat(TestText.render(Arrays.asList(new Line.SimpleLine("abc"), new Line.BlankLine())))
                .containsExactly("abc", "");
    }

}
