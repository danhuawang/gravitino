/*
 * Copyright 2024 Datastrato Inc.
 */
package org.apache.gravitino.server;

import java.lang.reflect.Field;
import java.util.Map;
import javax.servlet.Servlet;
import org.apache.gravitino.config.ConfigEntry;
import org.apache.gravitino.server.web.ConfigServlet;

final class ConfigServletConfigEntryRegistrar {

  private static final String JETTY_SERVER_FIELD_NAME = "server";
  private static final String SERVLET_CONTEXT_HANDLER_FIELD_NAME = "servletContextHandler";
  private static final String CONFIGS_FIELD_NAME = "configs";

  private ConfigServletConfigEntryRegistrar() {}

  static void register(GravitinoServer gravitinoServer, ConfigEntry<?> configEntry) {
    try {
      Object jettyServer =
          readField(GravitinoServer.class, JETTY_SERVER_FIELD_NAME, gravitinoServer, Object.class);
      Object servletContextHandler =
          readField(
              jettyServer.getClass(),
              SERVLET_CONTEXT_HANDLER_FIELD_NAME,
              jettyServer,
              Object.class);
      ConfigServlet configServlet = findConfigServlet(servletContextHandler);
      Map<String, Object> configs = readConfigs(configServlet);
      configs.put(configEntry.getKey(), gravitinoServer.serverConfig().get(configEntry));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Failed to register config entry " + configEntry.getKey() + " with ConfigServlet", e);
    }
  }

  private static ConfigServlet findConfigServlet(Object servletContextHandler)
      throws ReflectiveOperationException {
    Object servletHandler = invokeNoArg(servletContextHandler, "getServletHandler");
    for (Object holder : (Object[]) invokeNoArg(servletHandler, "getServlets")) {
      Servlet servlet = readField(holder, "_instance", Servlet.class);
      if (servlet instanceof ConfigServlet) {
        return (ConfigServlet) servlet;
      }
    }

    throw new IllegalStateException("ConfigServlet is not registered with the Jetty server");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readConfigs(ConfigServlet configServlet)
      throws ReflectiveOperationException {
    return readField(ConfigServlet.class, CONFIGS_FIELD_NAME, configServlet, Map.class);
  }

  private static Object invokeNoArg(Object target, String methodName)
      throws ReflectiveOperationException {
    return target.getClass().getMethod(methodName).invoke(target);
  }

  private static <T> T readField(
      Class<?> declaringClass, String fieldName, Object target, Class<T> fieldType)
      throws ReflectiveOperationException {
    Field field = declaringClass.getDeclaredField(fieldName);
    field.setAccessible(true);
    return fieldType.cast(field.get(target));
  }

  private static <T> T readField(Object target, String fieldName, Class<T> fieldType)
      throws ReflectiveOperationException {
    Class<?> currentClass = target.getClass();
    while (currentClass != null) {
      try {
        return readField(currentClass, fieldName, target, fieldType);
      } catch (NoSuchFieldException e) {
        currentClass = currentClass.getSuperclass();
      }
    }

    throw new NoSuchFieldException(fieldName);
  }
}
