package com.widen.util.td;

public class TestTd {

    public static void main(String[] args) throws Exception {
        DeadlockThread.startThreads();
        Thread.sleep(1000);
        JvmThreadDump out = new JvmThreadDump();
        System.out.println(out.generate());
    }

}
