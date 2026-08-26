/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.service;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import java.util.EnumSet;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Embedded Jetty 11 listener for the SCIM auxiliary service (Jakarta Servlet + Jersey 3). */
final class ScimJettyServer {

  private static final Logger LOG = LoggerFactory.getLogger(ScimJettyServer.class);

  private Server server;
  private ServletContextHandler context;

  /**
   * Initializes the SCIM HTTP listener from {@link JettyServerConfig}.
   *
   * @param serverConfig bind address and thread-pool settings
   */
  void initialize(JettyServerConfig serverConfig) {
    QueuedThreadPool threadPool =
        createThreadPool(
            serverConfig.getMinThreads(),
            serverConfig.getMaxThreads(),
            serverConfig.getThreadPoolWorkQueueSize());

    server = new Server(threadPool);
    server.setStopAtShutdown(true);
    server.setStopTimeout(serverConfig.getStopTimeout());

    ServerConnector connector = new ServerConnector(server);
    connector.setHost(serverConfig.getHost());
    connector.setPort(serverConfig.getHttpPort());
    connector.setReuseAddress(true);
    server.addConnector(connector);

    context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    context.setContextPath("/");
    server.setHandler(context);

    LOG.info(
        "SCIM Jetty server configured on {}:{}",
        serverConfig.getHost(),
        serverConfig.getHttpPort());
  }

  /**
   * Registers a servlet filter for the given path spec.
   *
   * @param filter servlet filter
   * @param pathSpec path mapping (for example {@code /scim/*})
   */
  void addFilter(Filter filter, String pathSpec) {
    context.addFilter(new FilterHolder(filter), pathSpec, EnumSet.of(DispatcherType.REQUEST));
  }

  /**
   * Registers a servlet for the given path spec.
   *
   * @param servlet servlet instance
   * @param pathSpec path mapping
   */
  void addServlet(HttpServlet servlet, String pathSpec) {
    context.addServlet(new ServletHolder(servlet), pathSpec);
  }

  /** Returns the Jetty thread pool backing this listener. */
  ThreadPool getThreadPool() {
    return server.getThreadPool();
  }

  /** Starts the embedded Jetty server. */
  void start() throws Exception {
    if (server != null) {
      server.start();
    }
  }

  /** Stops the embedded Jetty server. */
  void stop() throws Exception {
    if (server != null) {
      server.stop();
      server = null;
      context = null;
    }
  }

  private static QueuedThreadPool createThreadPool(
      int minThreads, int maxThreads, int threadPoolWorkQueueSize) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    return new QueuedThreadPool(
        maxThreads, minThreads, 60000, new LinkedBlockingQueue<>(threadPoolWorkQueueSize)) {
      @Override
      public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setContextClassLoader(classLoader);
        return thread;
      }
    };
  }
}
