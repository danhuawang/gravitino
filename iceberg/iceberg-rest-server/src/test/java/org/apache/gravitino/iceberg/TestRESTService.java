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
package org.apache.gravitino.iceberg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.codahale.metrics.MetricRegistry;
import io.prometheus.client.exporter.MetricsServlet;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.FederatedCatalogWrapper;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.IcebergRESTUtils;
import org.apache.gravitino.iceberg.service.provider.DynamicIcebergConfigProvider;
import org.apache.gravitino.iceberg.service.provider.StaticIcebergConfigProvider;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.metrics.MetricsSystem;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.server.web.HttpAuditFilter;
import org.apache.gravitino.server.web.JettyServer;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.apache.gravitino.server.web.JettyServerTestUtils;
import org.apache.gravitino.server.web.RequestContextFilter;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.eclipse.jetty.servlet.ServletHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class TestRESTService {

  @TempDir private Path warehouse;

  @Test
  void testGovernedTableCreateRequiresAuxiliaryGravitinoManagedCatalog() {
    StaticIcebergConfigProvider staticProvider = new StaticIcebergConfigProvider();
    DynamicIcebergConfigProvider dynamicProvider = new DynamicIcebergConfigProvider();

    assertFalse(
        RESTService.shouldEnableGovernedTableCreate(false, staticProvider),
        "Standalone IRC has no embedded policy or KMS services");
    assertFalse(
        RESTService.shouldEnableGovernedTableCreate(true, staticProvider),
        "Configuration-file catalog names are not Gravitino catalog identities");
    assertFalse(
        RESTService.shouldEnableGovernedTableCreate(false, dynamicProvider),
        "A Gravitino-managed catalog alone does not provide embedded policy or KMS services");
    assertTrue(
        RESTService.shouldEnableGovernedTableCreate(true, dynamicProvider),
        "Auxiliary dynamic catalogs must retain governed policy enforcement");
  }

  /**
   * The encryption dispatchers learn which catalogs are pass-through from the loaded catalog
   * itself: only a catalog served by {@link FederatedCatalogWrapper} is federated.
   */
  @Test
  void testFederatedCatalogIsTheOneServedByAFederatedWrapper() {
    IcebergCatalogWrapperManager manager = mock(IcebergCatalogWrapperManager.class);
    when(manager.getCatalogWrapper("federated")).thenReturn(mock(FederatedCatalogWrapper.class));
    when(manager.getCatalogWrapper("local")).thenReturn(mock(CatalogWrapperForREST.class));

    Predicate<String> federated = RESTService.federatedCatalog(manager);

    assertTrue(federated.test("federated"));
    assertFalse(federated.test("local"));
  }

  /**
   * RESTService.initServer() previously registered /metrics and /prometheus/metrics (added by
   * JettyServer#initialize() itself, outside ICEBERG_SPEC) with no audit coverage at all, and
   * nothing in the build caught it. Rather than parse source text, this exercises the extracted
   * RESTService#registerMetricsPathFilters against a plain JettyServer and inspects the real
   * ServletHandler filter mappings it produces, so a broken JettyServer.METRICS_PATH_SPECS list or
   * a filter that merely appears in a comment cannot pass. See GH-12760.
   */
  @Test
  public void testMetricsPathsHaveAuditFilterCoverage() throws Exception {
    JettyServer server = new JettyServer();
    JettyServerConfig jettyServerConfig = JettyServerConfig.fromConfig(new IcebergConfig());
    server.initialize(jettyServerConfig, "test-iceberg-rest", false);
    EventBus eventBus = new EventBus(Collections.emptyList());

    try {
      RESTService.registerMetricsPathFilters(server, eventBus);

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

  /** The documented Iceberg REST key reaches the value the service passes to its error builder. */
  @Test
  public void testIncludeErrorStackTraceReadFromServiceConfig() {
    assertFalse(JettyServerConfig.fromConfig(new IcebergConfig()).isIncludeErrorStackTrace());
    assertTrue(
        JettyServerConfig.fromConfig(
                new IcebergConfig(Collections.singletonMap("includeErrorStackTrace", "true")))
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
    Mockito.when(environment.config()).thenReturn(new Config(false) {});
    RuntimeException failure = new RuntimeException("failure");

    try (MockedStatic<GravitinoEnv> shared = Mockito.mockStatic(GravitinoEnv.class)) {
      shared.when(GravitinoEnv::getInstance).thenReturn(environment);

      RESTService enabledService = initializeService(Boolean.TRUE);
      try {
        ErrorResponse response =
            (ErrorResponse) IcebergRESTUtils.errorResponse(failure, 500).getEntity();
        assertTrue(String.join("\n", response.stack()).contains("RuntimeException: failure"));
      } finally {
        enabledService.serviceStop();
      }

      RESTService defaultService = initializeService(null);
      try {
        ErrorResponse response =
            (ErrorResponse) IcebergRESTUtils.errorResponse(failure, 500).getEntity();
        assertNull(response.stack());
      } finally {
        defaultService.serviceStop();
      }
    } finally {
      IcebergRESTUtils.setIncludeErrorStackTrace(false);
    }
  }

  private RESTService initializeService(Boolean includeErrorStackTrace) throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("host", "127.0.0.1");
    properties.put("httpPort", Integer.toString(RESTUtils.findAvailablePort(0, 0)));
    properties.put("warehouse", warehouse.toString());
    if (includeErrorStackTrace != null) {
      properties.put("includeErrorStackTrace", includeErrorStackTrace.toString());
    }
    RESTService service = new RESTService();
    service.serviceInit(properties, false);
    return service;
  }
}
