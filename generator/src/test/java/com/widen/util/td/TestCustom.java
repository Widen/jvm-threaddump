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

    static class MyObj {
        @Override
        public String toString() {
            return "Custom toString...";
        }
    }

    static class CustomImpl extends JvmThreadDumpImpl
    {
        @Override
        protected List<String> getMainArguments() {
            return Arrays.asList("foo", "blah");
        }

        @Override
        protected Map<String, Object> getCustomValues() {
            HashMap<String, Object> map = new HashMap<>();
            map.put("Hello", "World");
            map.put("Blah", new MyObj());
            return map;
        }
    }

    @Test
    public void testJvmThreadDump() {
        JvmThreadDumpImpl out = new CustomImpl();
        String dump = out.generate();
        assertThat(dump, containsString("Hello: World"));
        assertThat(dump, containsString("Blah: Custom toString..."));
        assertThat(dump, containsString("Main Arguments: foo blah"));
        System.out.println(dump);
    }

}
