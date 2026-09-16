/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.internal;

import com.datastrato.gravitino.server.web.InternalApiPaths;
import java.io.IOException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import org.apache.gravitino.rest.RESTUtils;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestInternalApiOriginFilter extends JerseyTest {

  /** Internal resource stub. */
  @Path(InternalApiPaths.PREFIX + "/stub")
  public static class InternalResource {

    /**
     * Returns an empty response.
     *
     * @return An empty response.
     */
    @GET
    public Response get() {
      return Response.ok().build();
    }
  }

  /** Internal resource stub that inherits its path. */
  public static class InheritedInternalResource extends InternalResource {}

  /** Public resource stub. */
  @Path("/public/stub")
  public static class PublicResource {

    /**
     * Returns an empty response.
     *
     * @return An empty response.
     */
    @GET
    public Response get() {
      return Response.ok().build();
    }
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return new ResourceConfig()
        .register(InternalApiOriginFilter.class)
        .register(InternalResource.class)
        .register(PublicResource.class);
  }

  @Test
  public void testInternalApiRejectsRequestWithoutHeader() {
    Response response = target(InternalApiPaths.PREFIX + "/stub").request().get();

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    Assertions.assertTrue(
        response.readEntity(String.class).contains(InternalApiOriginFilter.MISSING_HEADER_MESSAGE));
  }

  @Test
  public void testInternalApiRejectsBlankHeader() {
    Response response =
        target(InternalApiPaths.PREFIX + "/stub")
            .request()
            .header(InternalApiPaths.ORIGIN_HEADER, " ")
            .get();

    Assertions.assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  public void testInternalApiAcceptsRequestWithHeader() {
    Response response =
        target(InternalApiPaths.PREFIX + "/stub")
            .request()
            .header(InternalApiPaths.ORIGIN_HEADER, "gravitino-web")
            .get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  public void testPublicApiDoesNotRequireHeader() {
    Response response = target("/public/stub").request().get();

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  public void testOptionsRequestIsNotRejected() {
    Response response = target(InternalApiPaths.PREFIX + "/stub").request().options();

    Assertions.assertNotEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
  }

  @Test
  public void testIsInternalResource() {
    Assertions.assertTrue(InternalApiOriginFilter.isInternalResource(InternalResource.class));
    Assertions.assertTrue(
        InternalApiOriginFilter.isInternalResource(InheritedInternalResource.class));
    Assertions.assertFalse(InternalApiOriginFilter.isInternalResource(PublicResource.class));
    Assertions.assertFalse(InternalApiOriginFilter.isInternalResource(String.class));
    Assertions.assertFalse(InternalApiOriginFilter.isInternalResource(null));
  }

  @Test
  public void testIsInternalResourceMatchesWholeSegment() {
    Assertions.assertTrue(InternalApiOriginFilter.isInternalResource(RelativeInternalPath.class));
    Assertions.assertTrue(InternalApiOriginFilter.isInternalResource(InternalRootPath.class));
    Assertions.assertFalse(InternalApiOriginFilter.isInternalResource(InternalLikePath.class));
  }

  /** Resource path without a leading slash. */
  @Path("internal/relative")
  static class RelativeInternalPath {}

  /** Resource path equal to the internal prefix. */
  @Path("/internal/")
  static class InternalRootPath {}

  /** Resource path that only shares a prefix string with the internal prefix. */
  @Path("/internals/stub")
  static class InternalLikePath {}
}
