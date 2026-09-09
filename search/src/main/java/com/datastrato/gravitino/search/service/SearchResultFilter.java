/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.dto.SearchEntitiesDTO;
import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.store.SearchDataSource;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionEvaluator;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Filters matching entities before computing per-type pages and counts. */
final class SearchResultFilter {
  private static final Logger LOG = LoggerFactory.getLogger(SearchResultFilter.class);

  private SearchResultFilter() {}

  /**
   * Authorizes the matches produced by {@code source} and returns the caller's visible page and
   * visible total for every entity type.
   *
   * <p>Authorization is evaluated per matching entry, so at most {@code maxScanSize} entries of one
   * entity type are examined. Once that limit is reached the rest of that type is skipped in the
   * backend, and the type is reported with a truncated total, even when none of the examined
   * entries were visible. This bounds the authorization work, the per-request caches of {@code
   * context} and the batches fetched, for a query that matches far more entries than a caller can
   * page through.
   */
  static List<SearchEntitiesDTO> filter(
      SearchDataSource source,
      String metalake,
      int pageNumber,
      int pageSize,
      int maxScanSize,
      GravitinoAuthorizer authorizer) {
    Objects.requireNonNull(authorizer, "Search authorizer must be initialized");
    Preconditions.checkArgument(
        maxScanSize > 0, "maxScanSize must be greater than 0: %s", maxScanSize);
    long offset = (long) pageNumber * pageSize;
    Map<EntityType, TypeResults> results = new EnumMap<>(EntityType.class);
    AuthorizationRequestContext context = new AuthorizationRequestContext();
    for (SearchDataSource.Result batch = source.nextBatch();
        !batch.isEmpty();
        batch = source.nextBatch()) {
      EntityType type = batch.entityType();
      String expression = expression(type);
      if (expression == null) {
        // No authorization expression is defined for this type, so it is never disclosed.
        LOG.debug("Skipping search results of unauthorized entity type {}", type);
        continue;
      }
      TypeResults typeResults =
          results.computeIfAbsent(
              type,
              unused ->
                  new TypeResults(new AuthorizationExpressionEvaluator(expression, authorizer)));
      context.setOriginalAuthorizationExpression(expression);
      for (SearchEntityDTO entity : batch.entities()) {
        if (!typeResults.examine(metalake, type, maxScanSize)) {
          // Nothing left of this type can be returned or counted, so stop pulling its batches
          // out of the backend instead of deserializing them only to discard them.
          source.skipCurrentEntityType();
          break;
        }
        if (entity == null
            || entity.getEntityType() != type
            || !metalake.equals(entity.getMetalake())) {
          continue;
        }
        NameIdentifier identifier;
        try {
          identifier = identifier(metalake, entity);
        } catch (IllegalArgumentException e) {
          // A malformed indexed identifier must never be authorized as a different object.
          LOG.warn(
              "Skipping search result with an unparsable identifier, type {}, name {}",
              type,
              entity.getFullQualifiedName(),
              e);
          continue;
        }
        if (typeResults.evaluator.evaluate(
            NameIdentifierUtil.splitNameIdentifier(metalake, type, identifier), context)) {
          typeResults.accept(entity, offset, pageSize);
        }
      }
    }
    List<SearchEntitiesDTO> groups = new ArrayList<>();
    results.forEach(
        (type, typeResults) -> {
          // A type the caller sees nothing of is left out entirely, so neither its existence nor
          // its size is disclosed. A truncated type is the exception: the scan stopped before the
          // whole type was authorized, and an empty group carrying the truncation flag is what
          // tells the caller to narrow the query instead of reading the result as "no matches".
          if (typeResults.visible > 0 || typeResults.truncated) {
            groups.add(typeResults.toGroup(type));
          }
        });
    return groups;
  }

  /** The authorization state and visible results accumulated for one entity type. */
  private static final class TypeResults {
    private final AuthorizationExpressionEvaluator evaluator;
    private final List<SearchEntityDTO> page = new ArrayList<>();
    private int examined;
    private int visible;
    private boolean truncated;

    private TypeResults(AuthorizationExpressionEvaluator evaluator) {
      this.evaluator = evaluator;
    }

    /**
     * Claims one entry from this type's scan budget.
     *
     * @return whether the entry may be authorized, {@code false} once the budget is spent
     */
    private boolean examine(String metalake, EntityType type, int maxScanSize) {
      if (examined < maxScanSize) {
        examined++;
        return true;
      }
      if (!truncated) {
        truncated = true;
        LOG.warn(
            "Search in metalake {} stopped authorizing {} after {} matches, the reported total is"
                + " a lower bound; narrow the query or raise the authorization scan limit",
            metalake,
            type,
            maxScanSize);
      }
      return false;
    }

    /** Counts an authorized entry and keeps it when it falls in the requested page. */
    private void accept(SearchEntityDTO entity, long offset, int pageSize) {
      int position = visible;
      visible = Math.incrementExact(visible);
      if (position >= offset && position < offset + pageSize) {
        page.add(entity);
      }
    }

    private SearchEntitiesDTO toGroup(EntityType type) {
      return new SearchEntitiesDTO(visible, type, page, truncated);
    }
  }

  private static NameIdentifier identifier(String metalake, SearchEntityDTO entity) {
    EntityType type = entity.getEntityType();
    if (type == EntityType.ROLE) {
      return AuthorizationUtils.ofRole(metalake, entity.getEntityName());
    }
    if (type == EntityType.USER) {
      return AuthorizationUtils.ofUser(metalake, entity.getEntityName());
    }
    if (type == EntityType.GROUP) {
      return AuthorizationUtils.ofGroup(metalake, entity.getEntityName());
    }
    MetadataObject object =
        MetadataObjects.parse(
            entity.getFullQualifiedName(), MetadataObject.Type.valueOf(type.name()));
    return MetadataObjectUtil.toEntityIdent(metalake, object);
  }

  @Nullable
  private static String expression(EntityType type) {
    switch (type) {
      case CATALOG:
        return AuthorizationExpressionConstants.LOAD_CATALOG_AUTHORIZATION_EXPRESSION;
      case SCHEMA:
        return AuthorizationExpressionConstants.FILTER_SCHEMA_AUTHORIZATION_EXPRESSION;
      case TABLE:
        return AuthorizationExpressionConstants.FILTER_TABLE_AUTHORIZATION_EXPRESSION;
      case VIEW:
        return AuthorizationExpressionConstants.FILTER_VIEW_AUTHORIZATION_EXPRESSION;
      case FILESET:
        return AuthorizationExpressionConstants.FILTER_FILESET_AUTHORIZATION_EXPRESSION;
      case MODEL:
        return AuthorizationExpressionConstants.FILTER_MODEL_AUTHORIZATION_EXPRESSION;
      case TOPIC:
        return AuthorizationExpressionConstants.FILTER_TOPICS_AUTHORIZATION_EXPRESSION;
      case FUNCTION:
        return AuthorizationExpressionConstants.FILTER_FUNCTION_AUTHORIZATION_EXPRESSION;
      case TAG:
        return AuthorizationExpressionConstants.LOAD_TAG_AUTHORIZATION_EXPRESSION;
      case POLICY:
        return AuthorizationExpressionConstants.LOAD_POLICY_AUTHORIZATION_EXPRESSION;
      case ROLE:
        return AuthorizationExpressionConstants.LOAD_ROLE_AUTHORIZATION_EXPRESSION;
      case USER:
        return AuthorizationExpressionConstants.LOAD_USER_AUTHORIZATION_EXPRESSION;
      case GROUP:
        return AuthorizationExpressionConstants.LOAD_GROUP_AUTHORIZATION_EXPRESSION;
      default:
        return null;
    }
  }
}
