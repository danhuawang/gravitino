/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web;

import com.datastrato.gravitino.license.rest.LicenseStatusResource;
import com.datastrato.gravitino.metrics.rest.MetricOperations;
import com.datastrato.gravitino.scim.web.rest.ScimTokenOperations;
import com.datastrato.gravitino.search.rest.SearchOperations;
import com.datastrato.gravitino.server.web.rest.EntityOperations;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.ws.rs.Consumes;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Guards the boundary between public and internal Enterprise REST APIs on the main server.
 *
 * <p>Every Enterprise resource served under {@code /api} must live under {@link
 * InternalApiPaths#PREFIX} unless it is explicitly listed in {@link #PUBLIC_RESOURCES}. Adding a
 * public Enterprise endpoint is a deliberate decision: it needs an entry here and an OpenAPI spec.
 */
public class TestInternalApiPaths {

  /** Packages whose resources are registered on the main server's {@code /api} servlet. */
  private static final String[] MAIN_SERVER_PACKAGES = {
    EntityOperations.class.getPackageName(),
    SearchOperations.class.getPackageName(),
    MetricOperations.class.getPackageName(),
    ScimTokenOperations.class.getPackageName(),
    LicenseStatusResource.class.getPackageName()
  };

  /** Enterprise resources intentionally exposed as public APIs. */
  private static final Set<Class<?>> PUBLIC_RESOURCES =
      ImmutableSet.of(
          // Users need to query the license status outside the Web UI.
          LicenseStatusResource.class);

  @Test
  public void testEnterpriseResourcesAreInternalUnlessPublic() {
    Set<Class<?>> resources = scanResources();
    Assertions.assertTrue(
        resources.containsAll(
            ImmutableSet.of(
                EntityOperations.class,
                SearchOperations.class,
                MetricOperations.class,
                ScimTokenOperations.class,
                LicenseStatusResource.class)),
        "Resource scanning did not find the expected Enterprise resources: " + resources);

    String internalPrefix = InternalApiPaths.PREFIX + "/";
    for (Class<?> resource : resources) {
      String path = normalize(resource.getAnnotation(Path.class).value());
      if (PUBLIC_RESOURCES.contains(resource)) {
        Assertions.assertFalse(
            path.startsWith(internalPrefix),
            resource.getName() + " is listed as public but is served under " + path);
      } else {
        Assertions.assertTrue(
            path.startsWith(internalPrefix),
            resource.getName()
                + " must be served under "
                + InternalApiPaths.PREFIX
                + " or be listed in PUBLIC_RESOURCES, but is served under "
                + path);
      }
    }
  }

  @Test
  public void testEnterpriseResourceMethodsDoNotCollide() {
    Map<String, String> routes = new HashMap<>();
    for (Class<?> resource : scanResources()) {
      String classPath = resource.getAnnotation(Path.class).value();
      for (Method method : resource.getMethods()) {
        String httpMethod = httpMethod(method);
        if (httpMethod == null) {
          continue;
        }
        Path methodPath = method.getAnnotation(Path.class);
        Consumes consumes = method.getAnnotation(Consumes.class);
        String route =
            httpMethod
                + " "
                + normalize(classPath + "/" + (methodPath == null ? "" : methodPath.value()))
                + (consumes == null ? "" : " " + Arrays.toString(consumes.value()));
        String handler = resource.getName() + "#" + method.getName();
        String existing = routes.putIfAbsent(route, handler);
        Assertions.assertNull(
            existing, "Route " + route + " is defined by both " + existing + " and " + handler);
      }
    }
  }

  private static Set<Class<?>> scanResources() {
    return new ResourceConfig()
        .packages(true, MAIN_SERVER_PACKAGES).getClasses().stream()
            .filter(clazz -> clazz.isAnnotationPresent(Path.class))
            // Skip resource stubs declared inside unit tests that share these packages.
            .filter(clazz -> clazz.getEnclosingClass() == null)
            .filter(clazz -> !clazz.getSimpleName().startsWith("Test"))
            .collect(Collectors.toSet());
  }

  private static String httpMethod(Method method) {
    return Arrays.stream(method.getAnnotations())
        .map(annotation -> annotation.annotationType().getAnnotation(HttpMethod.class))
        .filter(httpMethod -> httpMethod != null)
        .map(HttpMethod::value)
        .findFirst()
        .orElse(null);
  }

  private static String normalize(String path) {
    String withoutParams = path.replaceAll("\\{[^}]*}", "{}");
    return "/"
        + Arrays.stream(withoutParams.split("/"))
            .filter(segment -> !segment.isEmpty())
            .collect(Collectors.joining("/"));
  }
}
