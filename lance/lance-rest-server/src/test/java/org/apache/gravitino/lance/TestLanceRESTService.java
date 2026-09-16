/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.lance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import io.prometheus.client.exporter.MetricsServlet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.lance.common.config.LanceConfig;
import org.apache.gravitino.lance.service.LanceExceptionMapper;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.authentication.ServerAuthenticator;
import org.apache.gravitino.server.web.HttpAuditFilter;
import org.apache.gravitino.server.web.JettyServer;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.apache.gravitino.server.web.JettyServerTestUtils;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.apache.gravitino.server.web.RequestContextFilter;
import org.eclipse.jetty.servlet.ServletHandler;
import org.junit.jupiter.api.Test;
import org.lance.namespace.errors.InvalidInputException;
import org.lance.namespace.model.ErrorResponse;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class TestLanceRESTService {

  /**
   * LanceRESTService.serviceInit() previously registered /metrics and /prometheus/metrics (added by
   * JettyServer#initialize() itself, outside LANCE_SPEC) with no audit coverage at all, and nothing
   * in the build caught it. Rather than parse source text, this exercises the extracted
   * LanceRESTService#registerMetricsPathFilters against a plain JettyServer and inspects the real
   * ServletHandler filter mappings it produces, so a broken JettyServer.METRICS_PATH_SPECS list or
   * a filter that merely appears in a comment cannot pass. See GH-12760.
   */
  @Test
  public void testMetricsPathsHaveAuditFilterCoverage() throws Exception {
    JettyServer server = new JettyServer();
    JettyServerConfig jettyServerConfig = JettyServerConfig.fromConfig(new LanceConfig());
    server.initialize(jettyServerConfig, "test-lance-rest", false);
    EventBus eventBus = new EventBus(Collections.emptyList());

    try {
      LanceRESTService.registerMetricsPathFilters(server, eventBus);

      ServletHandler servletHandler =
          JettyServerTestUtils.getServletContextHandler(server).getServletHandler();
      Set<String> auditedPathSpecs =
          JettyServerTestUtils.filterPathSpecsFor(servletHandler, HttpAuditFilter.class);
      Set<String> requestContextPathSpecs =
          JettyServerTestUtils.filterPathSpecsFor(servletHandler, RequestContextFilter.class);

      for (String pathSpec : JettyServer.METRICS_PATH_SPECS) {
        assertTrue(
            auditedPathSpecs.contains(pathSpec),
            "'" + pathSpec + "' must be covered by HttpAuditFilter, see GH-12760");
        assertTrue(
            requestContextPathSpecs.contains(pathSpec),
            "'"
                + pathSpec
                + "' must be covered by RequestContextFilter for query-parameter "
                + "capture, see GH-12760");
      }
    } finally {
      server.stop();
    }
  }

  /** The documented Lance REST key reaches the value the service passes to its error builder. */
  @Test
  public void testIncludeErrorStackTraceReadFromServiceConfig() {
    assertFalse(JettyServerConfig.fromConfig(new LanceConfig()).isIncludeErrorStackTrace());
    assertTrue(
        JettyServerConfig.fromConfig(
                new LanceConfig(Collections.singletonMap("includeErrorStackTrace", "true")))
            .isIncludeErrorStackTrace());
  }

  @Test
  public void testServiceInitAppliesAndResetsErrorStackTraceSetting() throws Exception {
    GravitinoEnv environment = Mockito.mock(GravitinoEnv.class);
    MetricsSystem metricsSystem = Mockito.mock(MetricsSystem.class);
    Mockito.when(metricsSystem.getMetricRegistry()).thenReturn(new MetricRegistry());
    Mockito.when(metricsSystem.getPrometheusServlet())
        .thenReturn(Mockito.mock(MetricsServlet.class));
    Mockito.when(environment.metricsSystem()).thenReturn(metricsSystem);
    Mockito.when(environment.eventBus()).thenReturn(new EventBus(Collections.emptyList()));
    String capturedStack =
        "java.lang.RuntimeException: Captured stacktrace\n\tat example.Stack.frame";
    InvalidInputException failure =
        new InvalidInputException("bad input", capturedStack, "instance");

    try (MockedStatic<GravitinoEnv> shared = Mockito.mockStatic(GravitinoEnv.class)) {
      shared.when(GravitinoEnv::getInstance).thenReturn(environment);

      LanceRESTService enabledService = initializeService(Boolean.TRUE);
      try (javax.ws.rs.core.Response response =
          LanceExceptionMapper.toRESTResponse("instance", failure)) {
        assertEquals(capturedStack, ((ErrorResponse) response.getEntity()).getDetail());
      } finally {
        enabledService.serviceStop();
      }

      LanceRESTService defaultService = initializeService(null);
      try (javax.ws.rs.core.Response response =
          LanceExceptionMapper.toRESTResponse("instance", failure)) {
        assertEquals("", ((ErrorResponse) response.getEntity()).getDetail());
      } finally {
        defaultService.serviceStop();
      }
    } finally {
      LanceExceptionMapper.setIncludeErrorStackTrace(false);
    }
  }

  /** Generic Jetty authentication uses the Lance service's default and explicit opt-in. */
  @Test
  public void testAuthenticationErrorStackTraceFollowsLanceConfig() throws Exception {
    assertFalse(requestAuthenticationFailure(Collections.emptyMap()).has("stack"));
    assertTrue(
        requestAuthenticationFailure(Collections.singletonMap("includeErrorStackTrace", "true"))
            .has("stack"));
  }

  private static JsonNode requestAuthenticationFailure(Map<String, String> properties)
      throws Exception {
    int port = RESTUtils.findAvailablePort(0, 0);
    Map<String, String> serverProperties = new HashMap<>(properties);
    serverProperties.put("host", "127.0.0.1");
    serverProperties.put("httpPort", Integer.toString(port));
    LanceConfig lanceConfig = new LanceConfig(serverProperties);
    JettyServerConfig serverConfig = JettyServerConfig.fromConfig(lanceConfig);
    ServerAuthenticator.getInstance().initialize(lanceConfig);
    JettyServer server = new JettyServer();
    try {
      server.initialize(serverConfig, "test-lance-authentication", false);
      server.addServlet(
          new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) {
              response.setStatus(HttpServletResponse.SC_OK);
            }
          },
          "/lance/*");
      server.addSystemFilters("/lance/*");
      server.start();
      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + port + "/lance/v1/config"))
                      .header(
                          AuthConstants.HTTP_HEADER_AUTHORIZATION,
                          AuthConstants.AUTHORIZATION_BEARER_HEADER + "unsupported")
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertEquals(401, response.statusCode());
      return ObjectMapperProvider.objectMapper(true).readTree(response.body());
    } finally {
      server.stop();
    }
  }

  private static LanceRESTService initializeService(Boolean includeErrorStackTrace)
      throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("host", "127.0.0.1");
    properties.put("httpPort", Integer.toString(RESTUtils.findAvailablePort(0, 0)));
    properties.put("gravitino-metalake", "test");
    if (includeErrorStackTrace != null) {
      properties.put("includeErrorStackTrace", includeErrorStackTrace.toString());
    }
    LanceRESTService service = new LanceRESTService();
    service.serviceInit(properties, false);
    return service;
  }
}
