# Simple JVM Thread Dump

This project formats JVM info as simple text. It works well as a zero-configuration debugging tool for web-based applications.

There are no classpath dependencies; all utility classes are included and package private.

## Usage

Direct instantiation:
```
  import com.widen.util.td.ThreadDumpServlet;

  JvmThreadDump out = new JvmThreadDump();
  String dump = out.generate();
  System.out.println(dump);
```

Servlet configuration:
```
  <web-app>
    <servlet>
      <servlet-name>threaddump</servlet-name>
      <servlet-class>com.widen.util.td.ThreadDumpServlet</servlet-class>
    </servlet>
    <servlet-mapping>
      <servlet-name>threaddump</servlet-name>
      <url-pattern>/td</url-pattern>
    </servlet-mapping>
  </web-app>
```

## Glossary

  - `Hostname`: `System.getenv('HOSTNAME')`
  - `CPU Load`: [System](https://docs.oracle.com/javase/8/docs/jre/api/management/extension/com/sun/management/OperatingSystemMXBean.html#getSystemCpuLoad--),
  [JVM](https://docs.oracle.com/javase/8/docs/jre/api/management/extension/com/sun/management/OperatingSystemMXBean.html#getProcessCpuLoad--)
  - `System Uptime`: Linux only; parsed from file `/proc/uptime`

! Example Output

```
          Current Time: 01/30/2019 11:18:17 CST
     Default Time Zone: Central Standard Time (America/Chicago)
          Java Version: 1.8.0_191-b12 (Oracle Corporation)
               Java VM: 25.191-b12 (mixed mode)
             Java Home: /Library/Java/JavaVirtualMachines/jdk1.8.0_191.jdk/Contents/Home/jre
     Working Directory: /Users/uriah/dev/github/jvm-threaddump/generator
        Temp Directory: /var/folders/ws/ydsf0xgn6mn849zk7rp25fgxl7sq5r/T/

        Main Arguments: -ea -Xmx256m -Didea.test.cyclic.buffer.size=1048576 -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=60184:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8
      Default Encoding: UTF-8
      JVM System Props: -Dfile.encoding=UTF-8
                        -Didea.test.cyclic.buffer.size=1048576

              Hostname: Not Available
      Operating System: Mac OS X (10.14.2)
     System Processors: 12 x86_64
         System Memory: 2,017 MB available; 32,768 MB total
              CPU Load: 0.0000 system; 0.0000 jvm
         System Uptime: Not Available
      System Idle Time: Not Available
            JVM Uptime: 0 days, 0:00:00
          JVM CPU Time: 0 days, 0:00:00

       JVM Free Memory: 232 MB (of committed)
      JVM Maximum Heap: 245 MB
       JVM Memory Args: -Xmx256m

                Memory: used/max
                  Heap: 5%/5%         init=256 MB      used=12 MB       commit=245 MB      max=245 MB
              Non Heap: 93%/0%        init=2 MB        used=9 MB        commit=10 MB       max=-1 bytes

             Collector: used/max
         PS Eden Space: 20%/20%       init=64 MB       used=12 MB       commit=64 MB       max=64 MB
     PS Survivor Space: 0%/0%         init=10 MB       used=0 bytes     commit=10 MB       max=10 MB
            PS Old Gen: 0%/0%         init=171 MB      used=0 bytes     commit=171 MB      max=171 MB
            Code Cache: 88%/1%        init=2 MB        used=2 MB        commit=2 MB        max=240 MB
Compressed Class Space: 81%/0%        init=0 bytes     used=827 KB      commit=1 MB        max=1,024 MB
             Metaspace: 96%/0%        init=0 bytes     used=6 MB        commit=7 MB        max=-1 bytes

        GC PS Scavenge: spent 0:00:00 doing 0 collections
       GC PS MarkSweep: spent 0:00:00 doing 0 collections

    Deadlocked Threads: None
          Thread Count: 5

"Finalizer" daemon priority=8 id=0x3 group=system cpu=0ms block_cnt=1 wait_cnt=2 WAITING
    java.lang.Object.wait(Object.java)
    java.lang.ref.ReferenceQueue.remove(ReferenceQueue.java:144)
    java.lang.ref.ReferenceQueue.remove(ReferenceQueue.java:165)
    java.lang.ref.Finalizer$FinalizerThread.run(Finalizer.java:216)

"main" priority=5 id=0x1 group=main cpu=405ms block_cnt=0 wait_cnt=0 RUNNABLE
    java.lang.Thread.dumpThreads(Thread.java)
    java.lang.Thread.getAllStackTraces(Thread.java:1610)
    com.widen.util.td.ThreadDumpProducer.getThreads(ThreadDumpProducer.java:24)
    com.widen.util.td.JvmThreadDump.doThreads(JvmThreadDump.java:217)
    com.widen.util.td.JvmThreadDump.generate(JvmThreadDump.java:58)
    com.widen.util.td.TestDump.dumpToStdOut(TestDump.java:14)
    sun.reflect.NativeMethodAccessorImpl.invoke0(NativeMethodAccessorImpl.java)
    sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
    sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
    java.lang.reflect.Method.invoke(Method.java:498)
    org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:50)
    org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
    org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:47)
    org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
    org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:325)
    org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:78)
    org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:57)
    org.junit.runners.ParentRunner$3.run(ParentRunner.java:290)
    org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:71)
    org.junit.runners.ParentRunner.runChildren(ParentRunner.java:288)
    org.junit.runners.ParentRunner.access$000(ParentRunner.java:58)
    org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:268)
    org.junit.runners.ParentRunner.run(ParentRunner.java:363)
    org.junit.runner.JUnitCore.run(JUnitCore.java:137)
    com.intellij.junit4.JUnit4IdeaTestRunner.startRunnerWithArgs(JUnit4IdeaTestRunner.java:68)
    com.intellij.rt.execution.junit.IdeaTestRunner$Repeater.startRunnerWithArgs(IdeaTestRunner.java:47)
    com.intellij.rt.execution.junit.JUnitStarter.prepareStreamsAndStart(JUnitStarter.java:242)
    com.intellij.rt.execution.junit.JUnitStarter.main(JUnitStarter.java:70)

"Monitor Ctrl-Break" daemon priority=5 id=0x5 group=main cpu=22ms block_cnt=0 wait_cnt=0 RUNNABLE
    java.net.SocketInputStream.socketRead0(SocketInputStream.java)
    java.net.SocketInputStream.socketRead(SocketInputStream.java:116)
    java.net.SocketInputStream.read(SocketInputStream.java:171)
    java.net.SocketInputStream.read(SocketInputStream.java:141)
    sun.nio.cs.StreamDecoder.readBytes(StreamDecoder.java:284)
    sun.nio.cs.StreamDecoder.implRead(StreamDecoder.java:326)
    sun.nio.cs.StreamDecoder.read(StreamDecoder.java:178)
    java.io.InputStreamReader.read(InputStreamReader.java:184)
    java.io.BufferedReader.fill(BufferedReader.java:161)
    java.io.BufferedReader.readLine(BufferedReader.java:324)
    java.io.BufferedReader.readLine(BufferedReader.java:389)
    com.intellij.rt.execution.application.AppMainV2$1.run(AppMainV2.java:64)

"Reference Handler" daemon priority=10 id=0x2 group=system cpu=0ms block_cnt=1 wait_cnt=1 WAITING
    java.lang.Object.wait(Object.java)
    java.lang.Object.wait(Object.java:502)
    java.lang.ref.Reference.tryHandlePending(Reference.java:191)
    java.lang.ref.Reference$ReferenceHandler.run(Reference.java:153)

"Signal Dispatcher" daemon priority=9 id=0x4 group=system cpu=0ms block_cnt=0 wait_cnt=0 RUNNABLE
```

## License

Apache, Version 2.0
