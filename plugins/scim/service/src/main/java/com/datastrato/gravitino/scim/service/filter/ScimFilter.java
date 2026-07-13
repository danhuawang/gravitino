/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.filter;

import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.basic.mapper.ScimNameMappers;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.directory.scim.spec.exception.ResourceException;
import org.apache.directory.scim.spec.exception.UnsupportedFilterException;
import org.apache.directory.scim.spec.filter.AttributeComparisonExpression;
import org.apache.directory.scim.spec.filter.CompareOperator;
import org.apache.directory.scim.spec.filter.Filter;
import org.apache.directory.scim.spec.filter.FilterExpression;
import org.apache.directory.scim.spec.filter.LogicalExpression;
import org.apache.directory.scim.spec.filter.LogicalOperator;

/** Converts SCIMple user filter expressions into adapter lookup criteria. */
public final class ScimFilter {

  private final Optional<String> externalId;
  private final Optional<String> userName;

  private ScimFilter(Optional<String> externalId, Optional<String> userName) {
    this.externalId = externalId;
    this.userName = userName;
  }

  /**
   * Converts a SCIM filter for supported {@code eq} / {@code and} predicates.
   *
   * @param filter SCIM filter or {@code null} for unfiltered list
   * @param config SCIM configuration for mapper application on userName
   * @return parsed criteria
   */
  public static ScimFilter convert(@Nullable Filter filter, ScimConfig config)
      throws ResourceException {
    if (filter == null || filter.getExpression() == null) {
      return new ScimFilter(Optional.empty(), Optional.empty());
    }
    return parseExpression(filter.getExpression(), config);
  }

  /** Returns external id equality value, if present. */
  public Optional<String> externalId() {
    return externalId;
  }

  /** Returns user name equality value, if present. */
  public Optional<String> userName() {
    return userName;
  }

  /** Returns whether the criteria contains any predicate. */
  public boolean hasPredicates() {
    return externalId.isPresent() || userName.isPresent();
  }

  private static ScimFilter parseExpression(FilterExpression expression, ScimConfig config)
      throws ResourceException {
    if (expression instanceof AttributeComparisonExpression comparison) {
      return parseComparison(comparison, config);
    }
    if (expression instanceof LogicalExpression logical) {
      if (logical.getOperator() != LogicalOperator.AND) {
        throw new UnsupportedFilterException("Only logical AND is supported");
      }
      ScimFilter left = parseExpression(logical.getLeft(), config);
      ScimFilter right = parseExpression(logical.getRight(), config);
      return merge(left, right);
    }
    throw new UnsupportedFilterException("Unsupported filter expression: " + expression);
  }

  private static ScimFilter parseComparison(
      AttributeComparisonExpression comparison, ScimConfig config) throws ResourceException {
    if (comparison.getOperation() != CompareOperator.EQ) {
      throw new UnsupportedFilterException("Only eq operator is supported");
    }
    String attribute = comparison.getAttributePath().getAttributeName();
    Object rawValue = comparison.getCompareValue();
    if (!(rawValue instanceof String)) {
      throw new ResourceException(400, "Filter compare value must be a non-blank string");
    }
    String value = (String) rawValue;
    if (value.isBlank()) {
      throw new ResourceException(400, "Filter compare value must be a non-blank string");
    }

    return switch (attribute) {
      case "externalId" -> new ScimFilter(Optional.of(value), Optional.empty());
      case "userName" -> new ScimFilter(
          Optional.empty(), Optional.of(ScimNameMappers.mapUserName(config.userMapper(), value)));
      default -> throw new UnsupportedFilterException("Unsupported filter attribute: " + attribute);
    };
  }

  private static ScimFilter merge(ScimFilter left, ScimFilter right) {
    return new ScimFilter(
        firstPresent(left.externalId(), right.externalId()),
        firstPresent(left.userName(), right.userName()));
  }

  private static Optional<String> firstPresent(Optional<String> first, Optional<String> second) {
    return first.isPresent() ? first : second;
  }
}
