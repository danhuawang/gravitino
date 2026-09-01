/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import org.junit.jupiter.api.Test;

class TestScimResourceLocation {

  @Test
  void testSingleResourceUsesAbsolutePath() throws Exception {
    UriInfo uriInfo = mock(UriInfo.class);
    when(uriInfo.getAbsolutePath()).thenReturn(URI.create("http://host/scim/v2/Users/42"));

    Response original =
        Response.ok().location(URI.create("http://host/scim/v2/Users/42/42")).build();

    Response fixed = ScimResourceLocation.forSingleResource(original, uriInfo);

    assertEquals(URI.create("http://host/scim/v2/Users/42"), fixed.getLocation());
  }

  @Test
  void testNullInputsReturnedUnchanged() {
    Response response = Response.ok().build();
    assertSame(response, ScimResourceLocation.forSingleResource(response, null));
    assertNull(ScimResourceLocation.forSingleResource(null, mock(UriInfo.class)));
  }
}
