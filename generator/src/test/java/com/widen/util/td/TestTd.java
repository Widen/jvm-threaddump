package com.widen.util.td;

public class TestTd {

    public static void main(String[] args) {
        //PrintWriter printer = new PrintWriter(System.out, true);
        //TextOutput output = new TextOutput(printer);

        TextOutput out = new TextOutput();
        System.out.println(out.generate());
    }

}
