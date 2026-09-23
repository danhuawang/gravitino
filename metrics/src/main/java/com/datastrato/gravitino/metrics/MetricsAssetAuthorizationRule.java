/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.utils.MetadataObjectUtil;

/**
 * Describes the authorization rule for one asset type used in dashboard metric calculations.
 *
 * <p>The expression-based fallback and the bit-set projection share these rules so their per-type
 * privileges cannot drift independently.
 */
final class MetricsAssetAuthorizationRule {
  private static final List<MetricsAssetAuthorizationRule> RULES =
      Collections.unmodifiableList(
          Arrays.asList(
              new MetricsAssetAuthorizationRule(
                  MetadataObject.Type.TABLE,
                  AuthorizationExpressionConstants.LOAD_TABLE_AUTHORIZATION_EXPRESSION,
                  AuthorizationExpressionConstants.FILTER_TABLE_AUTHORIZATION_EXPRESSION,
                  Privilege.Name.SELECT_TABLE,
                  Privilege.Name.MODIFY_TABLE),
              new MetricsAssetAuthorizationRule(
                  MetadataObject.Type.VIEW,
                  AuthorizationExpressionConstants.LOAD_VIEW_AUTHORIZATION_EXPRESSION,
                  AuthorizationExpressionConstants.FILTER_VIEW_AUTHORIZATION_EXPRESSION,
                  Privilege.Name.SELECT_VIEW),
              new MetricsAssetAuthorizationRule(
                  MetadataObject.Type.FUNCTION,
                  AuthorizationExpressionConstants.LOAD_FUNCTION_AUTHORIZATION_EXPRESSION,
                  AuthorizationExpressionConstants.FILTER_FUNCTION_AUTHORIZATION_EXPRESSION,
                  Privilege.Name.EXECUTE_FUNCTION,
                  Privilege.Name.MODIFY_FUNCTION),
              new MetricsAssetAuthorizationRule(
                  MetadataObject.Type.TOPIC,
                  AuthorizationExpressionConstants.LOAD_TOPICS_AUTHORIZATION_EXPRESSION,
                  AuthorizationExpressionConstants.FILTER_TOPICS_AUTHORIZATION_EXPRESSION,
                  Privilege.Name.CONSUME_TOPIC,
                  Privilege.Name.PRODUCE_TOPIC),
              new MetricsAssetAuthorizationRule(
                  MetadataObject.Type.FILESET,
                  AuthorizationExpressionConstants.LOAD_FILESET_AUTHORIZATION_EXPRESSION,
                  AuthorizationExpressionConstants.FILTER_FILESET_AUTHORIZATION_EXPRESSION,
                  Privilege.Name.READ_FILESET,
                  Privilege.Name.WRITE_FILESET),
              new MetricsAssetAuthorizationRule(
                  MetadataObject.Type.MODEL,
                  AuthorizationExpressionConstants.LOAD_MODEL_AUTHORIZATION_EXPRESSION,
                  AuthorizationExpressionConstants.FILTER_MODEL_AUTHORIZATION_EXPRESSION,
                  Privilege.Name.USE_MODEL)));

  private final MetadataObject.Type assetType;
  private final String loadExpression;
  private final String filterExpression;
  private final List<Privilege.Name> privileges;

  private MetricsAssetAuthorizationRule(
      MetadataObject.Type assetType,
      String loadExpression,
      String filterExpression,
      Privilege.Name... privileges) {
    this.assetType = assetType;
    this.loadExpression = loadExpression;
    this.filterExpression = filterExpression;
    this.privileges = Collections.unmodifiableList(Arrays.asList(privileges));
  }

  static List<MetricsAssetAuthorizationRule> rules() {
    return RULES;
  }

  MetadataObject.Type assetType() {
    return assetType;
  }

  Entity.EntityType entityType() {
    return MetadataObjectUtil.toEntityType(assetType);
  }

  String loadExpression() {
    return loadExpression;
  }

  String filterExpression() {
    return filterExpression;
  }

  List<Privilege.Name> privileges() {
    return privileges;
  }

  Set<AssetNode> nodes(MetalakeSnapshot snapshot) {
    switch (assetType) {
      case TABLE:
        return snapshot.getTableNodes();
      case VIEW:
        return snapshot.getViewNodes();
      case FUNCTION:
        return snapshot.getFunctionNodes();
      case FILESET:
        return snapshot.getFilesetNodes();
      case TOPIC:
        return snapshot.getTopicNodes();
      case MODEL:
        return snapshot.getModelNodes();
      default:
        throw new IllegalStateException("Unsupported dashboard metric asset type: " + assetType);
    }
  }
}
