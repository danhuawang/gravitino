/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import java.util.function.Function;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared helpers for listing metadata visible to the current user. */
final class MetadataListingHelper {

  private static final Logger LOG = LoggerFactory.getLogger(MetadataListingHelper.class);

  private MetadataListingHelper() {}

  static NameIdentifier[] listVisibleSchemaIdentifiers(
      SchemaDispatcher schemaDispatcher, Namespace namespace) {
    NameIdentifier[] schemaIdentifiers = schemaDispatcher.listSchemas(namespace);
    return filterByExpression(
        namespace.level(0),
        AuthorizationExpressionConstants.FILTER_SCHEMA_AUTHORIZATION_EXPRESSION,
        Entity.EntityType.SCHEMA,
        schemaIdentifiers,
        Function.identity());
  }

  static <E> E[] filterByExpression(
      String metalake,
      String expression,
      Entity.EntityType entityType,
      E[] entities,
      Function<E, NameIdentifier> toNameIdentifier) {
    try {
      return MetadataAuthzHelper.filterByExpression(
          metalake, expression, entityType, entities, toNameIdentifier);
    } catch (IllegalArgumentException e) {
      if (!isMetadataAuthorizationReady()) {
        LOG.warn(
            "Skip metadata authorization filtering for {} due to uninitialized GravitinoEnv.",
            entityType);
        return entities;
      }
      throw e;
    }
  }

  private static boolean isMetadataAuthorizationReady() {
    return GravitinoEnv.getInstance().config() != null
        && GravitinoEnv.getInstance().accessControlDispatcher() != null;
  }
}
