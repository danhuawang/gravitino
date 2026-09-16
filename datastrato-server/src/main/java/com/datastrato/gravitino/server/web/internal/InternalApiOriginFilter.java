/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.internal;

import com.datastrato.gravitino.server.web.InternalApiPaths;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.server.web.Utils;

/**
 * Rejects requests to internal REST APIs that do not carry {@link InternalApiPaths#ORIGIN_HEADER}.
 *
 * <p>The filter runs after resource matching and decides by the matched resource class, so it
 * covers every resource served under {@link InternalApiPaths#PREFIX} regardless of how the request
 * path is spelled. The header is not a secret: it stops clients other than the Web UI and bundled
 * tools from calling internal APIs by accident. Authorization of internal APIs is enforced
 * separately.
 */
public class InternalApiOriginFilter implements ContainerRequestFilter {

  static final String MISSING_HEADER_MESSAGE =
      "This is an internal API of the Datastrato Web UI and is not part of the public REST API.";

  @Context private ResourceInfo resourceInfo;

  /** Creates a filter that resolves the matched resource from the request context. */
  public InternalApiOriginFilter() {}

  @Override
  public void filter(ContainerRequestContext requestContext) {
    if (HttpMethod.OPTIONS.equals(requestContext.getMethod())
        || !isInternalResource(resourceInfo == null ? null : resourceInfo.getResourceClass())) {
      return;
    }
    if (StringUtils.isBlank(requestContext.getHeaderString(InternalApiPaths.ORIGIN_HEADER))) {
      requestContext.abortWith(Utils.illegalArguments(MISSING_HEADER_MESSAGE));
    }
  }

  /**
   * Returns whether the resource class is served under {@link InternalApiPaths#PREFIX}.
   *
   * @param resourceClass the matched resource class, or {@code null} if no resource matched
   * @return {@code true} if the class-level path starts with the internal prefix
   */
  static boolean isInternalResource(Class<?> resourceClass) {
    for (Class<?> clazz = resourceClass; clazz != null; clazz = clazz.getSuperclass()) {
      Path path = clazz.getAnnotation(Path.class);
      if (path != null) {
        String normalized = normalize(path.value());
        return normalized.equals(InternalApiPaths.PREFIX)
            || normalized.startsWith(InternalApiPaths.PREFIX + "/");
      }
    }
    return false;
  }

  private static String normalize(String path) {
    return "/"
        + Arrays.stream(path.split("/"))
            .filter(segment -> !segment.isEmpty())
            .collect(Collectors.joining("/"));
  }
}
