/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service;

import com.google.common.base.Preconditions;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Embedded Jetty 11 listener for the SCIM auxiliary service (Jakarta Servlet + Jersey 3). */
final class ScimJettyServer {

  private static final Logger LOG = LoggerFactory.getLogger(ScimJettyServer.class);

  private static final String HTTPS = "https";
  private static final String HTTP_PROTOCOL = "http/1.1";

  private Server server;
  private ServletContextHandler context;
  private JettyServerConfig serverConfig;

  /**
   * Initializes the SCIM HTTP or HTTPS listener from {@link JettyServerConfig}.
   *
   * <p>When {@code enableHttps} is true, only the HTTPS connector is started (same behavior as the
   * main Gravitino / Iceberg / Lance Jetty servers).
   *
   * @param serverConfig bind address, ports, and TLS settings
   */
  void initialize(JettyServerConfig serverConfig) {
    this.serverConfig = serverConfig;
    QueuedThreadPool threadPool =
        createThreadPool(
            serverConfig.getMinThreads(),
            serverConfig.getMaxThreads(),
            serverConfig.getThreadPoolWorkQueueSize());

    server = new Server(threadPool);
    server.setStopAtShutdown(true);
    server.setStopTimeout(serverConfig.getStopTimeout());

    if (serverConfig.isEnableHttps()) {
      Preconditions.checkArgument(
          StringUtils.isNotBlank(serverConfig.getKeyStorePath()),
          "If enables https, must set keyStorePath");
      Preconditions.checkArgument(
          StringUtils.isNotBlank(serverConfig.getKeyStorePassword()),
          "If enables https, must set keyStorePassword");
      Preconditions.checkArgument(
          StringUtils.isNotBlank(serverConfig.getManagerPassword()),
          "If enables https, must set managerPassword");
      if (serverConfig.isEnableClientAuth()) {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(serverConfig.getTrustStorePath()),
            "If enables the authentication of the client, must set trustStorePath");
        Preconditions.checkArgument(
            StringUtils.isNotBlank(serverConfig.getTrustStorePassword()),
            "If enables the authentication of the client, must set trustStorePassword");
      }
      server.addConnector(
          createHttpsServerConnector(
              server,
              serverConfig.getRequestHeaderSize(),
              serverConfig.getResponseHeaderSize(),
              serverConfig.getHost(),
              serverConfig.getHttpsPort(),
              serverConfig.getIdleTimeout(),
              serverConfig.getKeyStorePath(),
              serverConfig.getKeyStorePassword(),
              serverConfig.getManagerPassword(),
              serverConfig.getKeyStoreType(),
              serverConfig.getTlsProtocol(),
              serverConfig.getSupportedAlgorithms(),
              serverConfig.isEnableClientAuth(),
              serverConfig.getTrustStorePath(),
              serverConfig.getTrustStorePassword(),
              serverConfig.getTrustStoreType()));
    } else {
      server.addConnector(
          createHttpServerConnector(
              server,
              serverConfig.getRequestHeaderSize(),
              serverConfig.getResponseHeaderSize(),
              serverConfig.getHost(),
              serverConfig.getHttpPort(),
              serverConfig.getIdleTimeout()));
    }

    context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    context.setContextPath("/");
    server.setHandler(context);

    LOG.info(
        "SCIM Jetty server configured on {}:{} ({})",
        serverConfig.getHost(),
        listenPort(),
        serverConfig.isEnableHttps() ? "https" : "http");
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
      if (serverConfig != null && !serverConfig.isEnableHttps()) {
        LOG.warn("Users should use HTTPS to avoid exposing SCIM bearer tokens");
      }
    }
  }

  /** Stops the embedded Jetty server. */
  void stop() throws Exception {
    if (server != null) {
      server.stop();
      server = null;
      context = null;
      serverConfig = null;
    }
  }

  private int listenPort() {
    return serverConfig.isEnableHttps() ? serverConfig.getHttpsPort() : serverConfig.getHttpPort();
  }

  private static ServerConnector createHttpServerConnector(
      Server server,
      int reqHeaderSize,
      int respHeaderSize,
      String host,
      int port,
      int idleTimeout) {
    HttpConfiguration httpConfig = new HttpConfiguration();
    httpConfig.setRequestHeaderSize(reqHeaderSize);
    httpConfig.setResponseHeaderSize(respHeaderSize);
    httpConfig.setSendServerVersion(true);
    httpConfig.setIdleTimeout(idleTimeout);

    HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpConfig);
    ServerConnector connector =
        createServerConnector(server, new ConnectionFactory[] {httpConnectionFactory});
    connector.setHost(host);
    connector.setPort(port);
    connector.setReuseAddress(true);
    return connector;
  }

  @SuppressWarnings("deprecation")
  private static ServerConnector createHttpsServerConnector(
      Server server,
      int reqHeaderSize,
      int respHeaderSize,
      String host,
      int port,
      int idleTimeout,
      String keyStorePath,
      String keyStorePassword,
      String keyManagerPassword,
      String keyStoreType,
      Optional<String> tlsProtocol,
      Set<String> supportedAlgorithms,
      boolean isEnableClientAuth,
      String trustStorePath,
      String trustStorePassword,
      String trustStoreType) {
    HttpConfiguration httpConfig = new HttpConfiguration();
    httpConfig.setSecureScheme(HTTPS);
    httpConfig.setRequestHeaderSize(reqHeaderSize);
    httpConfig.setResponseHeaderSize(respHeaderSize);
    httpConfig.setSendServerVersion(true);
    httpConfig.setIdleTimeout(idleTimeout);
    httpConfig.setSecurePort(port);

    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStorePath(keyStorePath);
    sslContextFactory.setKeyStorePassword(keyStorePassword);
    sslContextFactory.setKeyManagerPassword(keyManagerPassword);
    sslContextFactory.setKeyStoreType(keyStoreType);
    tlsProtocol.ifPresent(sslContextFactory::setProtocol);
    if (!supportedAlgorithms.isEmpty()) {
      sslContextFactory.setIncludeCipherSuites(supportedAlgorithms.toArray(new String[0]));
    }
    if (isEnableClientAuth) {
      sslContextFactory.setNeedClientAuth(true);
      sslContextFactory.setTrustStorePath(trustStorePath);
      sslContextFactory.setTrustStorePassword(trustStorePassword);
      sslContextFactory.setTrustStoreType(trustStoreType);
    }
    httpConfig.addCustomizer(new SecureRequestCustomizer());
    HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpConfig);
    SslConnectionFactory sslConnectionFactory =
        new SslConnectionFactory(sslContextFactory, HTTP_PROTOCOL);
    ServerConnector connector =
        createServerConnector(
            server, new ConnectionFactory[] {sslConnectionFactory, httpConnectionFactory});
    connector.setHost(host);
    connector.setPort(port);
    connector.setReuseAddress(true);
    return connector;
  }

  private static ServerConnector createServerConnector(
      Server server, ConnectionFactory[] connectionFactories) {
    Scheduler serverExecutor =
        new ScheduledExecutorScheduler("scim-webserver-JettyScheduler", true);
    return new ServerConnector(server, null, serverExecutor, null, -1, -1, connectionFactories);
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
