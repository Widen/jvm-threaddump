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
    void dropsACallThatRepeatsTheOneAbove() {
        StackTraceElement[] stack = {
                new StackTraceElement("java.lang.Object", "wait", "Object.java", 502),
                new StackTraceElement("java.lang.Object", "wait", "Object.java", 328),
                FakeThreads.frame("com.example.Main", "main")};

        assertThat(StackFilter.kept(stack)).containsExactly(stack[0], stack[2]);
    }

    @Test
    void collapsesARunToItsEnds() {
        StackTraceElement[] stack = {
                FakeThreads.frame("sun.nio.ch.EPollArrayWrapper", "epollWait"),
                FakeThreads.frame("sun.nio.ch.EPollArrayWrapper", "poll"),
                FakeThreads.frame("sun.nio.ch.SelectorImpl", "select"),
                FakeThreads.frame("io.netty.channel.nio.SelectedSelectionKeySetSelector", "select"),
                FakeThreads.frame("io.netty.channel.nio.NioEventLoop", "select"),
                FakeThreads.frame("io.netty.channel.nio.NioEventLoop", "run"),
                FakeThreads.frame("io.netty.util.concurrent.SingleThreadEventExecutor$4", "run"),
                FakeThreads.frame("io.netty.util.internal.ThreadExecutorMap$2", "run"),
                FakeThreads.frame("java.lang.Thread", "run")};

        assertThat(StackFilter.kept(stack)).containsExactly(stack[0], stack[2], stack[3], stack[5]);
    }

    @Test
    void aHiddenFrameSplitsNeitherARunNorARepeat() {
        StackTraceElement[] stack = {
                FakeThreads.frame("org.apache.hc.client5.A", "x"),
                FakeThreads.frame("org.apache.hc.client5.B", "y"),
                FakeThreads.frame("org.apache.hc.client5.B$$Lambda$9", "proceed"),
                FakeThreads.frame("org.apache.hc.client5.B", "y"),
                FakeThreads.frame("org.apache.hc.client5.C", "z"),
                FakeThreads.frame("com.example.App", "run")};

        assertThat(StackFilter.kept(stack)).containsExactly(stack[0], stack[4], stack[5]);
    }

    @Test
    void dropsARepeatThatARunsEndsLeaveNextToEachOther() {
        StackTraceElement[] stack = {
                FakeThreads.frame("com.example.App", "poll"),
                new StackTraceElement("io.netty.X", "m", "X.java", 10),
                FakeThreads.frame("io.netty.Y", "n"),
                new StackTraceElement("io.netty.X", "m", "X.java", 20),
                FakeThreads.frame("com.example.App", "run")};

        assertThat(StackFilter.kept(stack)).containsExactly(stack[0], stack[1], stack[4]);
    }

    @Test
    void keepsBothFramesOfATwoFrameRun() {
        StackTraceElement[] stack = {
                FakeThreads.frame("com.amazonaws.A", "a"),
                FakeThreads.frame("com.amazonaws.B", "b"),
                FakeThreads.frame("com.example.App", "run")};

        assertThat(StackFilter.kept(stack)).containsExactly(stack);
    }

    @Test
    void trimsAnSqsReceive() {
        StackTraceElement[] stack = sqsReceiveStack();

        assertThat(StackFilter.kept(stack)).extracting(ThreadSection::formatFrame).containsExactly(
                "    java.net.SocketInputStream.socketRead0(SocketInputStream.java)",
                "    java.net.SocketInputStream.read(SocketInputStream.java:171)",
                "    sun.security.ssl.SSLSocketInputRecord.read(SSLSocketInputRecord.java:476)",
                "    sun.security.ssl.SSLSocketImpl$AppInputStream.read(SSLSocketImpl.java:980)",
                "    org.apache.hc.core5.http.impl.io.SessionInputBufferImpl.fillBuffer(SessionInputBufferImpl.java:149)",
                "    org.apache.hc.client5.http.classic.HttpClient.executeOpen(HttpClient.java:183)",
                "    software.amazon.awssdk.http.apache5.internal.impl.Apache5SdkHttpClient.executeOpen(Apache5SdkHttpClient.java:70)",
                "    software.amazon.awssdk.services.sqs.DefaultSqsClient.receiveMessage(DefaultSqsClient.java:1996)",
                "    com.widen.mica.sqs.impl.SqsConsumerBackgroundServiceFactory$Service.runOnce(SqsConsumerBackgroundServiceFactory.java:259)",
                "    com.widen.mica.sqs.impl.SqsConsumerBackgroundServiceFactory$Service.run(SqsConsumerBackgroundServiceFactory.java:211)",
                "    com.widen.mica.background.services.BackgroundServicesImpl$BackgroundServiceThread.run(BackgroundServicesImpl.java:148)");
    }

    @Test
    void trimsAnIdleJava17PoolThreadToThreeFrames() {
        StackTraceElement[] stack = {
                FakeThreads.frame("jdk.internal.misc.Unsafe", "park"),
                FakeThreads.frame("java.util.concurrent.locks.LockSupport", "park"),
                FakeThreads.frame("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode", "block"),
                FakeThreads.frame("java.util.concurrent.ForkJoinPool", "unmanagedBlock"),
                FakeThreads.frame("java.util.concurrent.ForkJoinPool", "managedBlock"),
                FakeThreads.frame("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject", "await"),
                FakeThreads.frame("java.util.concurrent.LinkedBlockingQueue", "take"),
                FakeThreads.frame("java.util.concurrent.ThreadPoolExecutor", "getTask"),
                FakeThreads.frame("java.util.concurrent.ThreadPoolExecutor", "runWorker"),
                FakeThreads.frame("java.util.concurrent.ThreadPoolExecutor$Worker", "run"),
                FakeThreads.frame("java.lang.Thread", "run")};

        assertThat(StackFilter.kept(stack)).containsExactly(stack[0], stack[6], stack[7]);
    }

    @Test
    void keepsTheFrameStackPileUpKeysOn() {
        StackTraceElement lease = FakeThreads.frame("org.apache.hc.core5.pool.StrictConnPool", "lease");
        StackTraceElement[] poolWait = {
                FakeThreads.frame("jdk.internal.misc.Unsafe", "park"),
                FakeThreads.frame("java.util.concurrent.locks.LockSupport", "parkNanos"),
                FakeThreads.frame("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject", "awaitNanos"),
                lease,
                FakeThreads.frame("org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager", "lease"),
                FakeThreads.frame("com.example.App", "call")};

        assertThat(StackFilter.firstKeptOutsideJdk(sqsReceiveStack()).getMethodName()).isEqualTo("fillBuffer");
        assertThat(StackFilter.firstKeptOutsideJdk(poolWait)).isSameAs(lease);
    }

    /**
     * The SQS consumer thread of the spec in short form: one or more frames for each class group of the original.
     */
    private static StackTraceElement[] sqsReceiveStack() {
        return new StackTraceElement[] {
                new StackTraceElement("java.net.SocketInputStream", "socketRead0", "SocketInputStream.java", -1),
                new StackTraceElement("java.net.SocketInputStream", "socketRead", "SocketInputStream.java", 116),
                new StackTraceElement("java.net.SocketInputStream", "read", "SocketInputStream.java", 171),
                new StackTraceElement("java.net.SocketInputStream", "read", "SocketInputStream.java", 141),
                new StackTraceElement("sun.security.ssl.SSLSocketInputRecord", "read", "SSLSocketInputRecord.java", 476),
                new StackTraceElement("sun.security.ssl.SSLSocketInputRecord", "readHeader", "SSLSocketInputRecord.java", 470),
                new StackTraceElement("sun.security.ssl.SSLSocketImpl", "readApplicationRecord", "SSLSocketImpl.java", 1500),
                new StackTraceElement("sun.security.ssl.SSLSocketImpl$AppInputStream", "read", "SSLSocketImpl.java", 980),
                new StackTraceElement("org.apache.hc.core5.http.impl.io.SessionInputBufferImpl", "fillBuffer", "SessionInputBufferImpl.java", 149),
                new StackTraceElement("org.apache.hc.core5.http.impl.io.SessionInputBufferImpl", "readLine", "SessionInputBufferImpl.java", 280),
                new StackTraceElement("org.apache.hc.core5.http.impl.io.HttpRequestExecutor", "execute", "HttpRequestExecutor.java", 136),
                new StackTraceElement("org.apache.hc.client5.http.impl.classic.ExecChainElement", "execute", "ExecChainElement.java", 51),
                new StackTraceElement("org.apache.hc.client5.http.impl.classic.ExecChainElement$$Lambda$9/0x0000000800c03000", "proceed", null, -1),
                new StackTraceElement("org.apache.hc.client5.http.impl.classic.ConnectExec", "execute", "ConnectExec.java", 120),
                new StackTraceElement("org.apache.hc.client5.http.impl.classic.InternalHttpClient", "doExecute", "InternalHttpClient.java", 174),
                new StackTraceElement("org.apache.hc.client5.http.classic.HttpClient", "executeOpen", "HttpClient.java", 183),
                new StackTraceElement("software.amazon.awssdk.http.apache5.internal.impl.Apache5SdkHttpClient", "executeOpen", "Apache5SdkHttpClient.java", 70),
                new StackTraceElement("software.amazon.awssdk.http.apache5.Apache5HttpClient", "access$700", "Apache5HttpClient.java", 120),
                new StackTraceElement("software.amazon.awssdk.http.apache5.Apache5HttpClient", "execute", "Apache5HttpClient.java", 300),
                new StackTraceElement("software.amazon.awssdk.core.internal.http.pipeline.stages.RetryableStage", "execute", "RetryableStage.java", 50),
                new StackTraceElement("software.amazon.awssdk.core.internal.handler.BaseSyncClientHandler", "execute", "BaseSyncClientHandler.java", 120),
                new StackTraceElement("software.amazon.awssdk.services.sqs.DefaultSqsClient", "receiveMessage", "DefaultSqsClient.java", 1996),
                new StackTraceElement("com.widen.mica.sqs.impl.SqsConsumerBackgroundServiceFactory$Service", "runOnce", "SqsConsumerBackgroundServiceFactory.java", 259),
                new StackTraceElement("com.widen.mica.sqs.impl.SqsConsumerBackgroundServiceFactory$Service", "run", "SqsConsumerBackgroundServiceFactory.java", 211),
                new StackTraceElement("com.widen.mica.background.services.BackgroundServicesImpl$BackgroundServiceThread", "run", "BackgroundServicesImpl.java", 148)};
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
