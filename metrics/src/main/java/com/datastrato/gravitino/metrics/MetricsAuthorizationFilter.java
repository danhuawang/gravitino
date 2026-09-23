/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AuthorizationRequestContext;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionEvaluator;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Evaluates one authorization expression synchronously and fails closed per entity. */
final class MetricsAuthorizationFilter {
  private static final Logger LOG = LoggerFactory.getLogger(MetricsAuthorizationFilter.class);

  private MetricsAuthorizationFilter() {}

  static <E> Set<E> filterVisibleEntities(
      String metalake,
      String expression,
      Entity.EntityType entityType,
      Collection<E> entities,
      Function<E, NameIdentifier> toNameIdentifier,
      Principal principal,
      GravitinoAuthorizer authorizer) {
    if (entities.isEmpty()) {
      return Collections.emptySet();
    }

    AuthorizationRequestContext requestContext = new AuthorizationRequestContext();
    requestContext.setOriginalAuthorizationExpression(expression);
    AuthorizationExpressionEvaluator evaluator =
        new AuthorizationExpressionEvaluator(expression, authorizer);
    try {
      return PrincipalUtils.doAs(
          principal,
          () -> {
            Set<E> visible = new HashSet<>();
            int failedEvaluations = 0;
            Exception firstFailure = null;
            for (E entity : entities) {
              try {
                NameIdentifier identifier = toNameIdentifier.apply(entity);
                if (evaluator.evaluate(
                    NameIdentifierUtil.splitNameIdentifier(metalake, entityType, identifier),
                    requestContext,
                    principal,
                    Optional.empty())) {
                  visible.add(entity);
                }
              } catch (Exception e) {
                failedEvaluations++;
                if (firstFailure == null) {
                  firstFailure = e;
                }
              }
            }
            if (failedEvaluations > 0) {
              LOG.error(
                  "Dashboard metric authorization evaluation failed: metalake={}, principal={}, "
                      + "entityType={}, failedEntities={}, totalEntities={}",
                  metalake,
                  principal.getName(),
                  entityType,
                  failedEvaluations,
                  entities.size(),
                  firstFailure);
            }
            return visible;
          });
    } catch (Exception e) {
      LOG.error(
          "Failed to establish the authorization context for principal {} in metalake {}",
          principal.getName(),
          metalake,
          e);
      return Collections.emptySet();
    }
  }
}
