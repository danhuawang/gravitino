/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.service.rest;

import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.adapter.ScimGroupRepositoryAdapter;
import com.datastrato.gravitino.scim.service.adapter.ScimUserRepositoryAdapter;
import com.datastrato.gravitino.scim.service.listener.ScimGroupEventDispatcher;
import com.datastrato.gravitino.scim.service.listener.ScimUserEventDispatcher;
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider;
import java.util.Collections;
import org.apache.directory.scim.core.repository.InvalidRepositoryException;
import org.apache.directory.scim.core.repository.RepositoryRegistry;
import org.apache.directory.scim.core.schema.SchemaRegistry;
import org.apache.directory.scim.server.configuration.ServerConfiguration;
import org.apache.directory.scim.server.exception.FilterParseExceptionMapper;
import org.apache.directory.scim.server.exception.GenericExceptionMapper;
import org.apache.directory.scim.server.exception.MutabilityExceptionMapper;
import org.apache.directory.scim.server.exception.ResourceExceptionMapper;
import org.apache.directory.scim.server.exception.ScimExceptionMapper;
import org.apache.directory.scim.server.exception.UnsupportedFilterExceptionMapper;
import org.apache.directory.scim.server.exception.UnsupportedOperationExceptionMapper;
import org.apache.directory.scim.server.exception.WebApplicationExceptionMapper;
import org.apache.directory.scim.server.rest.ScimJacksonXmlBindJsonProvider;
import org.apache.directory.scim.server.rest.ScimpleFeature;
import org.apache.directory.scim.spec.resources.ScimGroup;
import org.apache.directory.scim.spec.resources.ScimUser;
import org.apache.directory.scim.spec.schema.Schema;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;

/** Jersey 3 application wiring SCIMple resources under {@link ScimMetalakeResource}. */
public final class GravitinoScimApplication {

  private GravitinoScimApplication() {}

  /**
   * Builds the SCIMple Jersey application for the auxiliary listener.
   *
   * @param gravitinoConfig server configuration
   * @param scimConfig SCIM mapper configuration
   * @return configured Jersey {@link ResourceConfig}
   */
  public static ResourceConfig create(Config gravitinoConfig, ScimConfig scimConfig) {
    SchemaRegistry schemaRegistry = new SchemaRegistry();
    schemaRegistry.addSchema(ScimUser.class, Collections.emptyList());
    schemaRegistry.addSchema(ScimGroup.class, Collections.emptyList());
    // SCIMple ScimResource.id omits caseExact (defaults false); RFC 7643 requires true.
    markCommonIdCaseExact(schemaRegistry);
    // Advertise only attributes Gravitino synchronizes (design §8.3 /Schemas).
    ScimSchemaSupport.retainSupportedAttributes(schemaRegistry);

    RepositoryRegistry repositoryRegistry = new RepositoryRegistry(schemaRegistry);
    try {
      repositoryRegistry.registerRepository(
          ScimUser.class,
          new ScimUserEventDispatcher(
              GravitinoEnv.getInstance().eventBus(),
              new ScimUserRepositoryAdapter(gravitinoConfig, scimConfig)));
      repositoryRegistry.registerRepository(
          ScimGroup.class,
          new ScimGroupEventDispatcher(
              GravitinoEnv.getInstance().eventBus(),
              new ScimGroupRepositoryAdapter(gravitinoConfig, scimConfig)));
    } catch (InvalidRepositoryException e) {
      throw new IllegalStateException("Failed to register SCIM repository adapters", e);
    }

    ServerConfiguration serverConfiguration = ScimServerConfigurations.create();

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(ScimpleFeature.class);
    resourceConfig.register(ResourceExceptionMapper.class);
    resourceConfig.register(ScimExceptionMapper.class);
    resourceConfig.register(FilterParseExceptionMapper.class);
    resourceConfig.register(WebApplicationExceptionMapper.class);
    resourceConfig.register(UnsupportedOperationExceptionMapper.class);
    resourceConfig.register(UnsupportedFilterExceptionMapper.class);
    resourceConfig.register(MutabilityExceptionMapper.class);
    resourceConfig.register(GenericExceptionMapper.class);
    resourceConfig.register(JacksonJsonProvider.class);
    resourceConfig.register(ScimJacksonXmlBindJsonProvider.class);
    resourceConfig.register(ScimHealthOperations.class);
    resourceConfig.register(ScimMetalakeResource.class);

    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bind(schemaRegistry).to(SchemaRegistry.class);
            bind(repositoryRegistry).to(RepositoryRegistry.class);
            bind(serverConfiguration).to(ServerConfiguration.class);
          }
        });

    return resourceConfig;
  }

  /**
   * Sets common attribute {@code id} to {@code caseExact=true} on registered User/Group schemas.
   *
   * <p>Apache SCIMple annotates {@code ScimResource.id} without {@code caseExact}, so reflection
   * emits {@code false}. RFC 7643 Section 3.1 defines {@code id} as case-exact.
   *
   * @param schemaRegistry registry after User/Group schemas are registered
   */
  static void markCommonIdCaseExact(SchemaRegistry schemaRegistry) {
    for (Schema schema : schemaRegistry.getAllSchemas()) {
      Schema.Attribute id = schema.getAttribute("id");
      if (id != null) {
        id.setCaseExact(true);
      }
    }
  }
}
