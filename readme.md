# Simple JVM Thread Dump

This project formats JVM info as simple text. It works well as a
zero-configuration debugging tool for web-based applications.

The generator module has no dependencies; all utility classes are included and
package private.

The servlet module depends on the generator. It expects the servlet container or
your application to supply `javax.servlet:javax.servlet-api` 3.0.1 through 4.x.
Jakarta containers (Servlet 5 and later, such as Tomcat 10+ and Jetty 11+)
are not supported.

## Usage

Direct instantiation:

```java
import com.widen.util.td.JvmThreadDump;

String dump = JvmThreadDump.create().generate();
System.out.println(dump);
```

Servlet configuration:

```text
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

## Customization

Build a customized dump with `JvmThreadDump.builder()`:

- `mainArguments(List<String>)`
  - Prints the program arguments, such as the `args` of `main`. The default is
    an empty list.
- `customValues(Map<String, ?>)`
  - Useful if you have environment specific information to aid in debugging the
    application. Each entry prints as one titled line, in the map's iteration
    order.
  - `String.valueOf(...)` formats each value on every `generate()` call, so a
    value that changes between calls shows its current text.

```java
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.widen.util.td.JvmThreadDump;

Map<String, Object> values = new LinkedHashMap<>();
values.put("App", "cheese");
values.put("Environment", "test");

JvmThreadDump dump = JvmThreadDump.builder()
        .mainArguments(Arrays.asList(args))
        .customValues(values)
        .build();
String out = dump.generate();
```

A `JvmThreadDump` is immutable and safe to share between threads. The builder
copies the list and the map, so later changes to them leave the dump unchanged.

To serve a customized dump, extend `ThreadDumpServlet` and pass the built dump
to its constructor. The servlet regenerates the dump on every request.

```java
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.widen.util.td.JvmThreadDump;
import com.widen.util.td.ThreadDumpServlet;

public class MyAppThreadDumpServlet extends ThreadDumpServlet
{
    public MyAppThreadDumpServlet(String appName, String env) {
        super(JvmThreadDump.builder()
                .mainArguments(Arrays.asList("--app", appName))
                .customValues(values(appName, env))
                .build());
    }

    private static Map<String, Object> values(String appName, String env) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("App", appName);
        values.put("Environment", env);
        return values;
    }
}
```

A container instantiates a servlet from `web.xml` only through a public no-arg
constructor. Register a servlet that needs constructor arguments in code.

```java
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

public class ThreadDumpRegistration implements ServletContextListener
{
    @Override
    public void contextInitialized(ServletContextEvent event) {
        event.getServletContext()
                .addServlet("threaddump", new MyAppThreadDumpServlet("cheese", "test"))
                .addMapping("/td");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
    }
}
```

## Glossary

- `Hostname`: `System.getenv('HOSTNAME')`
- `CPU Load`:
  [System](https://docs.oracle.com/javase/8/docs/jre/api/management/extension/com/sun/management/OperatingSystemMXBean.html#getSystemCpuLoad--),
  [JVM](https://docs.oracle.com/javase/8/docs/jre/api/management/extension/com/sun/management/OperatingSystemMXBean.html#getProcessCpuLoad--)
- `System Uptime`: Linux only; parsed from file `/proc/uptime`
- `Main Arguments`: Empty unless the builder sets `mainArguments`.
- `JVM System Props`: The `-D` values print as `****` when the key contains
  `password`, `secret`, `token`, `credential`, or `key`, or when the value
  contains one of those words followed by `=`.

## Example Output

```text
          Current Time: 01/30/2019 11:18:17 CST
     Default Time Zone: Central Standard Time (America/Chicago)
          Java Version: 1.8.0_191-b12 (Oracle Corporation)
               Java VM: 25.191-b12 (mixed mode)
             Java Home: /Library/Java/JavaVirtualMachines/jdk1.8.0_191.jdk/Contents/Home/jre
     Working Directory: /Users/uriah/dev/github/jvm-threaddump/generator
        Temp Directory: /var/folders/ws/ydsf0xgn6mn849zk7rp25fgxl7sq5r/T/

        Main Arguments:
      Default Encoding: UTF-8
      JVM System Props: file.encoding=UTF-8
                        idea.test.cyclic.buffer.size=1048576

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

                Memory: used/committed, used/max
                  Heap: 5%/5%         init=256 MB      used=12 MB       commit=245 MB      max=245 MB
              Non Heap: 93%/0%        init=2 MB        used=9 MB        commit=10 MB       max=-1 bytes

             Collector: used/committed, used/max
         PS Eden Space: 20%/20%       init=64 MB       used=12 MB       commit=64 MB       max=64 MB
     PS Survivor Space: 0%/0%         init=10 MB       used=0 bytes     commit=10 MB       max=10 MB
            PS Old Gen: 0%/0%         init=171 MB      used=0 bytes     commit=171 MB      max=171 MB
            Code Cache: 88%/1%        init=2 MB        used=2 MB        commit=2 MB        max=240 MB
Compressed Class Space: 81%/0%        init=0 bytes     used=827 KB      commit=1 MB        max=1,024 MB
             Metaspace: 96%/0%        init=0 bytes     used=6 MB        commit=7 MB        max=-1 bytes

        GC PS Scavenge: spent 0:00:00.000 doing 0 collections
       GC PS MarkSweep: spent 0:00:00.000 doing 0 collections

    Deadlocked Threads: None
          Thread Count: 5

"Finalizer" daemon priority=8 id=0x3 group=system cpu=0ms block_cnt=1 wait_cnt=2 WAITING
    java.lang.Object.wait(Object.java)
    java.lang.ref.ReferenceQueue.remove(ReferenceQueue.java:144)
    java.lang.ref.ReferenceQueue.remove(ReferenceQueue.java:165)
    java.lang.ref.Finalizer$FinalizerThread.run(Finalizer.java:216)

"Test worker" priority=5 id=0x1 group=main cpu=405ms block_cnt=0 wait_cnt=0 RUNNABLE
    sun.management.ThreadImpl.dumpThreads0(Native Method)
    sun.management.ThreadImpl.dumpAllThreads(ThreadImpl.java:454)
    com.widen.util.td.ThreadSection.lines(ThreadSection.java:38)
    com.widen.util.td.JvmThreadDump.generate(JvmThreadDump.java:53)
    com.widen.util.td.TestDump.printsEverySectionOfTheRunningJvm(TestDump.java:12)
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

## Build

Install the tools, then run the tests on Java 17 and Java 8:

```sh
mise install
./gradlew check
```

`mise.toml` installs Java 21, which runs Gradle, and Corretto 8, which it
exposes to Gradle through the `JDK8` variable. The build compiles with a Java
17 toolchain and `--release 8`, so the JARs run on Java 8 and later. `test`
runs the tests on Java 17, and `testOnJava8` runs them on Java 8. Gradle
downloads a missing JDK.

When the JFrog credentials described below are set, dependencies resolve
through the Acquia JFrog `widen-java-virtual` repository. Without them,
dependencies resolve from Maven Central, so building and testing need no
credentials.

The version lives in `gradle.properties`. To publish to Acquia JFrog, run:

```sh
./gradlew publish
```

A version that ends in `-SNAPSHOT` goes to the `libs-snapshots-local`
repository. Any other version goes to `libs-releases-local`. Publishing needs
the credentials in `ACQUIA_JFROG_USER` and `ACQUIA_JFROG_TOKEN`. Set them as
environment variables, or as Gradle properties of the same names, for example
in `~/.gradle/gradle.properties`.

The wrapper pins the Gradle distribution's checksum in
`gradle/wrapper/gradle-wrapper.properties`. To upgrade Gradle, take the
checksum from
`https://services.gradle.org/distributions/gradle-VERSION-bin.zip.sha256` and
run:

```sh
./gradlew wrapper --gradle-version VERSION --gradle-distribution-sha256-sum SUM
```

## License

Apache, Version 2.0
