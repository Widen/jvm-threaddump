package com.widen.util.td;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

public class TestStackFilter
{
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "sun.reflect.NativeMethodAccessorImpl|true",
            "jdk.internal.reflect.DirectMethodHandleAccessor|true",
            "java.lang.reflect.Method|true",
            "java.lang.invoke.LambdaForm$MH|true",
            "$AssetService_1a2b3c4d|true",
            "$AssetService_1a2b3c4d$Invocation_find_5e6f|true",
            "com.sun.proxy.$Proxy12|true",
            "jdk.proxy2.$Proxy12|true",
            "com.example.Dao$$EnhancerByCGLIB$$1f|true",
            "com.example.Jobs$$Lambda$14/0x0000000800c03000|true",
            "org.apache.tapestry5.ioc.internal.RegistryImpl|true",
            "org.apache.tapestry5.commons.internal.services.PlasticProxyFactoryImpl|true",
            "org.apache.tapestry5.http.TapestryFilter|true",
            "org.eclipse.jetty.ee8.servlet.ServletHandler|true",
            "org.glassfish.jersey.server.ServerRuntime|true",
            "org.glassfish.hk2.utilities.Binder|true",
            "org.jvnet.hk2.internal.ServiceLocatorImpl|true",
            "javax.servlet.http.HttpServlet|true",
            "jakarta.servlet.http.HttpServlet|true",
            "org.apache.catalina.core.StandardWrapperValve|true",
            "org.apache.coyote.http11.Http11Processor|true",
            "org.apache.tomcat.util.net.NioEndpoint$SocketProcessor|true",
            "org.apache.tomcat.websocket.server.WsFilter|true",
            "org.apache.tomcat.jdbc.pool.ConnectionPool|false",
            "com.example.Dao|false",
            "com.example.Dao$Inner|false",
            "com.example.$Odd|false",
            "java.lang.Object|false",
            "org.postgresql.core.PGStream|false",
            "com.zaxxer.hikari.pool.HikariPool|false",
            "org.hibernate.loader.Loader|false"})
    void hidesPlumbingFrames(String className, boolean hidden) {
        assertThat(StackFilter.hidden(FakeThreads.frame(className, "run"))).isEqualTo(hidden);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "java.util.concurrent.locks.LockSupport|park|true",
            "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject|await|true",
            "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode|block|true",
            "java.util.concurrent.locks.AbstractQueuedLongSynchronizer|acquire|true",
            "java.util.concurrent.ThreadPoolExecutor|runWorker|true",
            "java.util.concurrent.ThreadPoolExecutor$Worker|run|true",
            "java.util.concurrent.FutureTask|run|true",
            "java.util.concurrent.FutureTask|runAndReset|true",
            "java.util.concurrent.Executors$RunnableAdapter|call|true",
            "java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask|run|true",
            "java.util.concurrent.ForkJoinWorkerThread|run|true",
            "java.util.concurrent.ForkJoinPool|runWorker|true",
            "java.util.concurrent.ForkJoinPool|managedBlock|true",
            "java.util.concurrent.ForkJoinPool|unmanagedBlock|true",
            "java.lang.Thread|run|true",
            "java.lang.Thread|runWith|true",
            "io.netty.util.concurrent.SingleThreadEventExecutor$4|run|true",
            "io.netty.util.internal.ThreadExecutorMap$2|run|true",
            "io.netty.util.concurrent.FastThreadLocalRunnable|run|true",
            "software.amazon.awssdk.http.apache5.Apache5HttpClient|access$700|true",
            "com.example.Outer|access$000|true",
            "java.util.concurrent.ThreadPoolExecutor|getTask|false",
            "java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue|take|false",
            "java.util.concurrent.LinkedBlockingQueue|take|false",
            "java.util.concurrent.locks.ReentrantLock|lock|false",
            "java.lang.Thread|sleep|false",
            "io.netty.channel.nio.NioEventLoop|run|false",
            "io.netty.util.concurrent.SingleThreadEventExecutor|runAllTasks|false",
            "com.example.Outer|accessible|false",
            "com.example.Outer|access$|false",
            "com.example.Outer|lambda$run$0|false"})
    void hidesExecutorNettyAndAccessorPlumbing(String className, String method, boolean hidden) {
        assertThat(StackFilter.hidden(FakeThreads.frame(className, method))).isEqualTo(hidden);
    }

    @Test
    void keepsTheTopFrameEvenWhenHidden() {
        StackTraceElement[] stack = {
                FakeThreads.frame("org.eclipse.jetty.io.ManagedSelector", "select"),
                FakeThreads.frame("java.lang.reflect.Method", "invoke"),
                FakeThreads.frame("com.example.A", "run")};

        List<StackTraceElement> kept = StackFilter.kept(stack);

        assertThat(kept).containsExactly(stack[0], stack[2]);
    }

    @Test
    void findsTheFirstKeptFrameOutsideTheJdk() {
        StackTraceElement receive = FakeThreads.frame("org.postgresql.core.PGStream", "receive");
        StackTraceElement[] stack = {
                FakeThreads.frame("sun.nio.ch.NioSocketImpl", "read"),
                FakeThreads.frame("$Dao_1a", "find"),
                receive,
                FakeThreads.frame("com.example.Dao", "find")};
        StackTraceElement[] jdkOnly = {
                FakeThreads.frame("java.lang.Object", "wait"),
                FakeThreads.frame("java.lang.Thread", "run")};

        assertThat(StackFilter.firstKeptOutsideJdk(stack)).isSameAs(receive);
        assertThat(StackFilter.firstKeptOutsideJdk(jdkOnly)).isNull();
        assertThat(StackFilter.firstKeptOutsideJdk(new StackTraceElement[0])).isNull();
    }
}
