package com.widen.util.td;

public class TestTd {

    public static void main(String[] args) {
        //PrintWriter printer = new PrintWriter(System.out, true);
        //JvmThreadDump output = new JvmThreadDump(printer);

        JvmThreadDump out = new JvmThreadDump();
        System.out.println(out.generate());
    }

}
