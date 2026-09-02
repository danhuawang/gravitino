/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ResourceContext;
import jakarta.ws.rs.core.Context;
import org.apache.directory.scim.core.repository.RepositoryRegistry;
import org.apache.directory.scim.core.schema.SchemaRegistry;
import org.apache.directory.scim.server.configuration.ServerConfiguration;
import org.apache.directory.scim.server.rest.EtagGenerator;
import org.apache.directory.scim.server.rest.ResourceTypesResourceImpl;
import org.apache.directory.scim.server.rest.SchemaResourceImpl;
import org.apache.directory.scim.server.rest.ServiceProviderConfigResourceImpl;

/** JAX-RS sub-resource locator for instance-scoped SCIM endpoints. */
@Path("v2")
public class ScimRootResource {

  private final SchemaRegistry schemaRegistry;
  private final RepositoryRegistry repositoryRegistry;
  private final ServerConfiguration serverConfiguration;
  private final EtagGenerator etagGenerator;

  @Context private ResourceContext resourceContext;

  @Inject
  public ScimRootResource(
      SchemaRegistry schemaRegistry,
      RepositoryRegistry repositoryRegistry,
      ServerConfiguration serverConfiguration) {
    this.schemaRegistry = schemaRegistry;
    this.repositoryRegistry = repositoryRegistry;
    this.serverConfiguration = serverConfiguration;
    this.etagGenerator = new EtagGenerator();
  }

  @Path("Users")
  public GravitinoUserResourceImpl users() {
    return resourceContext.initResource(
        new GravitinoUserResourceImpl(schemaRegistry, repositoryRegistry));
  }

  @Path("Groups")
  public GravitinoGroupResourceImpl groups() {
    return resourceContext.initResource(
        new GravitinoGroupResourceImpl(schemaRegistry, repositoryRegistry));
  }

  @Path("ServiceProviderConfig")
  public ServiceProviderConfigResourceImpl serviceProviderConfig() {
    return resourceContext.initResource(
        new ServiceProviderConfigResourceImpl(serverConfiguration, etagGenerator));
  }

  @Path("ResourceTypes")
  public ResourceTypesResourceImpl resourceTypes() {
    return resourceContext.initResource(new ResourceTypesResourceImpl(schemaRegistry));
  }

  @Path("Schemas")
  public SchemaResourceImpl schemas() {
    return resourceContext.initResource(new SchemaResourceImpl(schemaRegistry));
  }
}
