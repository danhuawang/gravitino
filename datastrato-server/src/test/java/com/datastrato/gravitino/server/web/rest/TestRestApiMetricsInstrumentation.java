/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.reflect.ClassPath;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import org.apache.gravitino.metrics.MetricNames;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Guards the metrics instrumentation of every enterprise REST resource.
 *
 * <p>The Jersey listener registered by {@code HttpServerMetricsSource} only meters resource methods
 * that carry {@link Timed} and {@link ResponseMetered}, so an un-annotated endpoint silently
 * disappears from {@code /metrics} — nothing fails, the numbers are simply missing. These tests
 * fail when a new endpoint is added without the annotations, when a metric name is reused, or when
 * a UI-facing endpoint drops the {@code web-} prefix.
 *
 * <p>The scanned packages are those the enterprise server loads: the three listed under {@code
 * gravitino.server.rest.extensionPackages} in {@code gravitino.conf}, the license resource
 * registered by {@code DatastratoGravitinoServer}, and the resources contributed by the SCIM and
 * IdP plugins.
 */
public class TestRestApiMetricsInstrumentation {

  private static final List<String> REST_PACKAGES =
      ImmutableList.of(
          "com.datastrato.gravitino.server.web.rest",
          "com.datastrato.gravitino.search.rest",
          "com.datastrato.gravitino.metrics.rest",
          "com.datastrato.gravitino.license.rest",
          "com.datastrato.gravitino.scim.web.rest",
          "org.apache.gravitino.idp.web.rest");

  /**
   * Resources served under {@code /web} are the ones the Enterprise UI calls, and several of them
   * mirror an open-source endpoint of the same name. Their metrics carry this prefix so the two
   * stay distinguishable and so a dashboard can select the whole UI surface at once.
   */
  private static final String WEB_METRIC_PREFIX = "web-";

  private static final List<Class<? extends Annotation>> HTTP_METHOD_ANNOTATIONS =
      ImmutableList.of(
          GET.class, POST.class, PUT.class, DELETE.class, PATCH.class, HEAD.class, OPTIONS.class);

  private static Map<String, List<Method>> resourceMethodsByPackage;

  @BeforeAll
  public static void scanClasspath() throws IOException {
    resourceMethodsByPackage = new HashMap<>();
    ClassPath classPath = ClassPath.from(TestRestApiMetricsInstrumentation.class.getClassLoader());
    for (String restPackage : REST_PACKAGES) {
      List<Method> methods = Lists.newArrayList();
      for (ClassPath.ClassInfo classInfo : classPath.getTopLevelClasses(restPackage)) {
        Class<?> clazz = classInfo.load();
        if (clazz.getAnnotation(Path.class) == null) {
          continue;
        }
        for (Method method : clazz.getDeclaredMethods()) {
          if (HTTP_METHOD_ANNOTATIONS.stream().anyMatch(a -> method.getAnnotation(a) != null)) {
            methods.add(method);
          }
        }
      }
      resourceMethodsByPackage.put(restPackage, methods);
    }
  }

  @Test
  public void testAllResourceMethodsAreInstrumented() {
    List<String> violations = Lists.newArrayList();

    for (Method method : resourceMethods()) {
      String name = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
      Timed timed = method.getAnnotation(Timed.class);
      ResponseMetered metered = method.getAnnotation(ResponseMetered.class);

      if (timed == null) {
        violations.add(name + " is missing @Timed");
      }
      if (metered == null) {
        violations.add(name + " is missing @ResponseMetered");
      }
      if (timed == null || metered == null) {
        continue;
      }

      if (!timed.absolute()) {
        violations.add(name + " must declare @Timed(absolute = true)");
      }
      if (!metered.absolute()) {
        violations.add(name + " must declare @ResponseMetered(absolute = true)");
      }
      if (isWebFacing(method) && !metered.name().startsWith(WEB_METRIC_PREFIX)) {
        violations.add(
            name
                + " is served under /web but its metric name '"
                + metered.name()
                + "' does not start with '"
                + WEB_METRIC_PREFIX
                + "'");
      }
      String expectedTimerName = metered.name() + "." + MetricNames.HTTP_PROCESS_DURATION;
      if (!expectedTimerName.equals(timed.name())) {
        violations.add(
            name
                + " has mismatched metric names: @Timed is '"
                + timed.name()
                + "' but '"
                + expectedTimerName
                + "' is expected from @ResponseMetered '"
                + metered.name()
                + "'");
      }
    }

    assertTrue(violations.isEmpty(), "Un-instrumented REST endpoints found: " + violations);
  }

  @Test
  public void testMetricNamesAreUnique() {
    Map<String, String> owners = new HashMap<>();
    List<String> duplicates = Lists.newArrayList();

    for (Method method : resourceMethods()) {
      ResponseMetered metered = method.getAnnotation(ResponseMetered.class);
      if (metered == null) {
        continue;
      }
      String owner = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
      String previous = owners.put(metered.name(), owner);
      if (previous != null) {
        duplicates.add(metered.name() + " is used by both " + previous + " and " + owner);
      }
    }

    assertTrue(duplicates.isEmpty(), "Duplicated REST metric names found: " + duplicates);
  }

  @Test
  public void testEveryPackageContributesResources() {
    // Without this, a package that is renamed or dropped from the classpath would make the tests
    // above pass by scanning nothing at all.
    List<String> empty = Lists.newArrayList();
    resourceMethodsByPackage.forEach(
        (restPackage, methods) -> {
          if (methods.isEmpty()) {
            empty.add(restPackage);
          }
        });

    assertTrue(
        empty.isEmpty(),
        "No REST resource methods were discovered in these packages, so they are not being "
            + "guarded: "
            + empty);
  }

  private static boolean isWebFacing(Method method) {
    Path path = method.getDeclaringClass().getAnnotation(Path.class);
    if (path == null) {
      return false;
    }
    // Class-level paths are written both with and without a leading slash in this codebase.
    String value = path.value().startsWith("/") ? path.value().substring(1) : path.value();
    return value.equals("web") || value.startsWith("web/");
  }

  private static List<Method> resourceMethods() {
    List<Method> all = Lists.newArrayList();
    resourceMethodsByPackage.values().forEach(all::addAll);
    return all;
  }
}
