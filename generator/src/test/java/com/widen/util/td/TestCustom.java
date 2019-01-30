package com.widen.util.td;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;

public class TestCustom
{

    static class CustomImpl extends JvmThreadDump
    {
        @Override
        protected List<String> getMainArguments() {
            return Arrays.asList("foo", "blah");
        }

        @Override
        protected Map<String, String> getCustomValues() {
            HashMap<String, String> map = new HashMap<>();
            map.put("Hello", "World");
            map.put("Blah", "Foo");
            return map;
        }
    }

    @Test
    public void testJvmThreadDump() {
        JvmThreadDump out = new CustomImpl();
        String dump = out.generate();
        assertThat(dump, containsString("Hello: World"));
        assertThat(dump, containsString("Blah: Foo"));
        assertThat(dump, containsString("Main Arguments: foo blah"));
        System.out.println(dump);
    }

}
