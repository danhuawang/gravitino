/*
 * Copyright 2024 Datastrato Inc.
 */
package org.apache.gravitino.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.preview.DataPreviewConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.server.web.ConfigServlet;
import org.apache.gravitino.server.web.JettyServer;
import org.apache.gravitino.server.web.JettyServerConfig;
import org.apache.gravitino.server.web.ObjectMapperProvider;
import org.junit.jupiter.api.Test;

class TestConfigServletConfigEntryRegistrar {

  @Test
  void testRegisterBooleanConfigEntry() throws Exception {
    ServerConfig serverConfig = new ServerConfig(false);
    Properties properties = new Properties();
    properties.setProperty(DataPreviewConfig.ENABLED_CONFIG.getKey(), "false");
    serverConfig.loadFromProperties(properties);

    GravitinoServer gravitinoServer = new GravitinoServer(serverConfig, mock(GravitinoEnv.class));
    JettyServer jettyServer = getJettyServer(gravitinoServer);
    try {
      jettyServer.initialize(
          JettyServerConfig.fromConfig(serverConfig, GravitinoServer.WEBSERVER_CONF_PREFIX),
          "test",
          false);
      ConfigServlet configServlet = new ConfigServlet(serverConfig);
      jettyServer.addServlet(configServlet, "/configs");

      ConfigServletConfigEntryRegistrar.register(gravitinoServer, DataPreviewConfig.ENABLED_CONFIG);

      Map<String, Object> configs = fetchConfigs(configServlet);
      assertEquals(Boolean.FALSE, configs.get(DataPreviewConfig.ENABLED_CONFIG.getKey()));
    } finally {
      jettyServer.stop();
    }
  }

  private static JettyServer getJettyServer(GravitinoServer gravitinoServer) throws Exception {
    Field field = GravitinoServer.class.getDeclaredField("server");
    field.setAccessible(true);
    return (JettyServer) field.get(gravitinoServer);
  }

  private static Map<String, Object> fetchConfigs(ConfigServlet configServlet) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter output = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(output));

    Method doGet =
        ConfigServlet.class.getDeclaredMethod(
            "doGet", HttpServletRequest.class, HttpServletResponse.class);
    doGet.setAccessible(true);
    doGet.invoke(configServlet, request, response);

    return ObjectMapperProvider.objectMapper()
        .readValue(output.toString(), new TypeReference<Map<String, Object>>() {});
  }
}
