/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */

package com.datastrato.gravitino.scim.v2.service.rest;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.apache.directory.scim.core.repository.RepositoryRegistry;
import org.apache.directory.scim.core.schema.SchemaRegistry;
import org.apache.directory.scim.protocol.data.PatchRequest;
import org.apache.directory.scim.protocol.exception.ScimException;
import org.apache.directory.scim.server.rest.GroupResourceImpl;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.filter.attribute.AttributeReferenceListWrapper;
import org.apache.directory.scim.spec.resources.ScimGroup;

/**
 * Group resource that fixes SCIMple {@code 1.0.0-M1} {@code Location} for single-resource calls.
 *
 * <p>{@code create} keeps SCIMple's collection-path + id behavior. {@code getById} / {@code update}
 * / {@code patch} set {@code Location} to {@link UriInfo#getAbsolutePath()}, matching upstream
 * SCIMple {@code develop}.
 */
public final class GravitinoGroupResourceImpl extends GroupResourceImpl {

  @Context private UriInfo uriInfo;

  /**
   * Creates a Group resource backed by the given registries.
   *
   * @param schemaRegistry SCIMple schema registry
   * @param repositoryRegistry SCIMple repository registry
   */
  public GravitinoGroupResourceImpl(
      SchemaRegistry schemaRegistry, RepositoryRegistry repositoryRegistry) {
    super(schemaRegistry, repositoryRegistry);
  }

  @Override
  public Response getById(
      String id,
      AttributeReferenceListWrapper attributes,
      AttributeReferenceListWrapper excludedAttributes)
      throws ScimException, ResourceException {
    return ScimResourceLocation.forSingleResource(
        super.getById(id, attributes, excludedAttributes), uriInfo);
  }

  @Override
  public Response update(
      ScimGroup resource,
      String id,
      AttributeReferenceListWrapper attributes,
      AttributeReferenceListWrapper excludedAttributes)
      throws ScimException, ResourceException {
    return ScimResourceLocation.forSingleResource(
        super.update(resource, id, attributes, excludedAttributes), uriInfo);
  }

  @Override
  public Response patch(
      PatchRequest patchRequest,
      String id,
      AttributeReferenceListWrapper attributes,
      AttributeReferenceListWrapper excludedAttributes)
      throws ScimException, ResourceException {
    return ScimResourceLocation.forSingleResource(
        super.patch(patchRequest, id, attributes, excludedAttributes), uriInfo);
  }
}
