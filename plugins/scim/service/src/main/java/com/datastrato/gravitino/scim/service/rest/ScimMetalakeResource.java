/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.rest;

import jakarta.ws.rs.Path;
import org.apache.directory.scim.core.repository.RepositoryRegistry;
import org.apache.directory.scim.core.schema.SchemaRegistry;
import org.apache.directory.scim.server.configuration.ServerConfiguration;
import org.apache.directory.scim.server.rest.EtagGenerator;
import org.apache.directory.scim.server.rest.GroupResourceImpl;
import org.apache.directory.scim.server.rest.ResourceTypesResourceImpl;
import org.apache.directory.scim.server.rest.SchemaResourceImpl;
import org.apache.directory.scim.server.rest.ServiceProviderConfigResourceImpl;
import org.apache.directory.scim.server.rest.UserResourceImpl;

/**
 * JAX-RS sub-resource locator for metalake-scoped SCIM 2.0 endpoints.
 *
 * <p>Mounts SCIMple {@code UserResourceImpl} / {@code GroupResourceImpl} and discovery resources
 * under {@code /scim/v2/metalakes/{metalake}} instead of registering {@code ScimResourceHelper}
 * top-level {@code /Users} routes.
 */
@Path("v2/metalakes/{metalake}")
public class ScimMetalakeResource {

  private final SchemaRegistry schemaRegistry;
  private final RepositoryRegistry repositoryRegistry;
  private final ServerConfiguration serverConfiguration;
  private final EtagGenerator etagGenerator;

  /**
   * Creates a metalake-scoped SCIM resource locator.
   *
   * @param schemaRegistry SCIMple schema registry
   * @param repositoryRegistry SCIMple repository registry
   * @param serverConfiguration advertised SCIM capabilities
   */
  public ScimMetalakeResource(
      SchemaRegistry schemaRegistry,
      RepositoryRegistry repositoryRegistry,
      ServerConfiguration serverConfiguration) {
    this.schemaRegistry = schemaRegistry;
    this.repositoryRegistry = repositoryRegistry;
    this.serverConfiguration = serverConfiguration;
    this.etagGenerator = new EtagGenerator();
  }

  /** Returns the Users sub-resource. */
  @Path("Users")
  public UserResourceImpl users() {
    return new UserResourceImpl(schemaRegistry, repositoryRegistry);
  }

  /** Returns the Groups sub-resource. */
  @Path("Groups")
  public GroupResourceImpl groups() {
    return new GroupResourceImpl(schemaRegistry, repositoryRegistry);
  }

  /** Returns the ServiceProviderConfig sub-resource. */
  @Path("ServiceProviderConfig")
  public ServiceProviderConfigResourceImpl serviceProviderConfig() {
    return new ServiceProviderConfigResourceImpl(serverConfiguration, etagGenerator);
  }

  /** Returns the ResourceTypes sub-resource. */
  @Path("ResourceTypes")
  public ResourceTypesResourceImpl resourceTypes() {
    return new ResourceTypesResourceImpl(schemaRegistry);
  }

  /** Returns the Schemas sub-resource. */
  @Path("Schemas")
  public SchemaResourceImpl schemas() {
    return new SchemaResourceImpl(schemaRegistry);
  }
}
