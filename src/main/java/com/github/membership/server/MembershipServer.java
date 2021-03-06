package com.github.membership.server;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.membership.lib.Lifecycle;
import com.github.membership.server.MembershipDelegate.DelegateMode;

import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.util.TransmitStatusRuntimeExceptionInterceptor;

public final class MembershipServer implements Lifecycle {
    private static final Logger logger = LogManager.getLogger(MembershipServer.class.getSimpleName());

    private final String identity = UUID.randomUUID().toString();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);

    private final DelegateMode delegateMode = DelegateMode.CURATOR;
    // private final DelegateMode delegateMode = DelegateMode.ZK_DIRECT;

    private MembershipServerConfiguration serverConfig;
    private MembershipServiceImpl service;

    private MembershipDelegate delegate;
    private Server server;
    private Thread serverThread;
    private ThreadPoolExecutor serverExecutor;

    public MembershipServer(final MembershipServerConfiguration serverConfig) {
        this.serverConfig = serverConfig;
    }

    // Minimally invasive way to reconnect to backend without recycling either
    // the MembershipServer or MembershipClient.
    public void reloadServerConfig(final String connectString) throws Exception {
        logger.info("Hot-reloading MembershipServer [{}] configuration", getIdentity());
        if (isRunning()) {
            if (delegate != null) {
                this.delegate.stop();
                // if connectString is null, keep existing one
                if (connectString != null && !connectString.trim().isEmpty()) {
                    this.serverConfig.setConnectString(connectString);
                }
                this.delegate = MembershipDelegate.getDelegate(serverConfig, delegateMode);
                delegate.start();
                service.setDelegate(delegate);
                logger.info("Reloaded MembershipServer [{}] configuration", getIdentity());
            }
        }
    }

    @Override
    public String getIdentity() {
        return identity;
    }

    @Override
    public void start() throws Exception {
        if (running.compareAndSet(false, true)) {
            final CountDownLatch serverReadyLatch = new CountDownLatch(1);
            serverThread = new Thread() {
                {
                    setName("membership-server-main");
                    setDaemon(true);
                }

                @Override
                public void run() {
                    final long startNanos = System.nanoTime();
                    logger.info("Starting MembershipServer [{}] at port {}", getIdentity(),
                            serverConfig.getServerPort());
                    try {
                        delegate = MembershipDelegate.getDelegate(serverConfig, delegateMode);
                        delegate.start();

                        serverExecutor = (ThreadPoolExecutor) Executors
                                .newFixedThreadPool(serverConfig.getWorkerCount(), new ThreadFactory() {
                                    private final AtomicInteger threadIter = new AtomicInteger();
                                    private final String threadNamePattern = "membership-server-%d";

                                    @Override
                                    public Thread newThread(final Runnable runnable) {
                                        final Thread worker = new Thread(runnable,
                                                String.format(threadNamePattern, threadIter.incrementAndGet()));
                                        worker.setDaemon(true);
                                        return worker;
                                    }
                                });
                        service = new MembershipServiceImpl();
                        service.setDelegate(delegate);
                        server = NettyServerBuilder
                                .forAddress(new InetSocketAddress(serverConfig.getServerHost(),
                                        serverConfig.getServerPort()))
                                .addService(service).intercept(TransmitStatusRuntimeExceptionInterceptor.instance())
                                .executor(serverExecutor).build();
                        server.start();
                        serverReadyLatch.countDown();
                        logger.info("Started MembershipServer [{}] at port {} in {} millis", getIdentity(),
                                serverConfig.getServerPort(),
                                TimeUnit.MILLISECONDS.convert(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS));
                        server.awaitTermination();
                    } catch (Exception serverProblem) {
                        logger.error("Failed to start MembershipServer [{}] at port {} in {} millis", getIdentity(),
                                serverConfig.getServerPort(),
                                TimeUnit.MILLISECONDS.convert(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS),
                                serverProblem);
                    }
                }
            };
            serverThread.start();
            if (serverReadyLatch.await(5L, TimeUnit.SECONDS)) {
                ready.set(true);
            } else {
                logger.error("Failed to start MembershipServer [{}] at port", getIdentity(),
                        serverConfig.getServerPort());
            }
        } else {
            logger.error("Invalid attempt to start an already running MembershipServer");
        }
    }

    @Override
    public void stop() throws Exception {
        final long startNanos = System.nanoTime();
        logger.info("Stopping MembershipServer [{}]", getIdentity());
        if (running.compareAndSet(true, false)) {
            ready.set(false);
            if (!server.isTerminated()) {
                server.shutdown();
                server.awaitTermination(2L, TimeUnit.SECONDS);
                serverThread.interrupt();
                logger.info("Stopped membership server main thread");
            }
            if (serverExecutor != null && !serverExecutor.isTerminated()) {
                serverExecutor.shutdown();
                serverExecutor.awaitTermination(2L, TimeUnit.SECONDS);
                serverExecutor.shutdownNow();
                logger.info("Stopped membership server worker threads");
            }
            if (delegate.isRunning()) {
                delegate.stop();
            }
            logger.info("Stopped MembershipServer [{}] in {} millis", getIdentity(),
                    TimeUnit.MILLISECONDS.convert(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS));
        } else {
            logger.error("Invalid attempt to stop an already stopped MembershipServer");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get() && ready.get();
    }

}
