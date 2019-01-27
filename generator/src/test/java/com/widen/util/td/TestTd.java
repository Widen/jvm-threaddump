package com.widen.util.td;

import java.io.PrintWriter;

public class TestTd {

    public static void main(String[] args) {
        PrintWriter printer = new PrintWriter(System.out, true);
        TextOutput output = new TextOutput(printer);
    }

}
