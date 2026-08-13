/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Corrects SCIM {@code Location} headers for single-resource responses.
 *
 * <p>SCIMple {@code 1.0.0-M1} {@code BaseResourceTypeResourceImpl#buildLocationTag} always builds
 * {@code uriInfo.getAbsolutePathBuilder().path(id)}. That is correct for {@code POST /Users}
 * (collection URI + id), but wrong for {@code GET}/{@code PUT}/{@code PATCH} {@code /Users/{id}}
 * where {@link UriInfo#getAbsolutePath()} already ends with the resource id, producing {@code
 * .../Users/{id}/{id}}.
 *
 * <p>Upstream SCIMple on {@code develop} fixed this by using {@code uriInfo.getAbsolutePath()} for
 * get/update/patch and only appending {@code id} on create. Until we upgrade past {@code 1.0.0-M1},
 * single-resource handlers re-apply that same rule.
 */
final class ScimResourceLocation {

  private ScimResourceLocation() {}

  /**
   * Replaces {@code Location} with the request absolute path (already the resource URI).
   *
   * @param response response from SCIMple single-resource handling
   * @param uriInfo current request URI info
   * @return response with corrected {@code Location}, or the original response when inputs are null
   */
  static Response forSingleResource(Response response, UriInfo uriInfo) {
    if (response == null || uriInfo == null) {
      return response;
    }
    return Response.fromResponse(response).location(uriInfo.getAbsolutePath()).build();
  }
}
