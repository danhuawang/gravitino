/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.scim.service.filter;

import com.datastrato.gravitino.scim.service.ScimConfig;
import com.datastrato.gravitino.scim.service.basic.mapper.ScimNameMappers;
import java.util.Locale;
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

/** Converts User SCIMple filter expressions into adapter lookup criteria. */
public final class ScimUserFilter {

  private final Optional<String> id;
  private final Optional<String> externalId;
  private final Optional<String> userName;

  private ScimUserFilter(
      Optional<String> id, Optional<String> externalId, Optional<String> userName) {
    this.id = id;
    this.externalId = externalId;
    this.userName = userName;
  }

  /**
   * Converts a User SCIM filter for supported {@code eq} / {@code and} predicates.
   *
   * <p>Supported attributes: {@code id}, {@code userName}, {@code externalId}.
   *
   * @param filter SCIM filter or {@code null} for unfiltered list
   * @param config SCIM configuration for mapper application on name attributes
   * @return parsed criteria
   */
  public static ScimUserFilter convert(@Nullable Filter filter, ScimConfig config)
      throws ResourceException {
    if (filter == null || filter.getExpression() == null) {
      return empty();
    }
    return parseExpression(filter.getExpression(), config);
  }

  /** Returns resource id equality value, if present. */
  public Optional<String> id() {
    return id;
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
    return id.isPresent() || externalId.isPresent() || userName.isPresent();
  }

  private static ScimUserFilter empty() {
    return new ScimUserFilter(Optional.empty(), Optional.empty(), Optional.empty());
  }

  private static ScimUserFilter parseExpression(FilterExpression expression, ScimConfig config)
      throws ResourceException {
    if (expression instanceof AttributeComparisonExpression comparison) {
      return parseComparison(comparison, config);
    }
    if (expression instanceof LogicalExpression logical) {
      if (logical.getOperator() != LogicalOperator.AND) {
        throw new UnsupportedFilterException("Only logical AND is supported");
      }
      ScimUserFilter left = parseExpression(logical.getLeft(), config);
      ScimUserFilter right = parseExpression(logical.getRight(), config);
      return merge(left, right);
    }
    throw new UnsupportedFilterException("Unsupported filter expression: " + expression);
  }

  private static ScimUserFilter parseComparison(
      AttributeComparisonExpression comparison, ScimConfig config) throws ResourceException {
    String attribute = comparison.getAttributePath().getAttributeName();
    return switch (attribute.toLowerCase(Locale.ROOT)) {
      case "id" -> new ScimUserFilter(
          Optional.of(parseEqScalarValue(comparison)), Optional.empty(), Optional.empty());
      case "externalid" -> new ScimUserFilter(
          Optional.empty(), Optional.of(parseEqStringValue(comparison)), Optional.empty());
      case "username" -> new ScimUserFilter(
          Optional.empty(),
          Optional.empty(),
          Optional.of(
              ScimNameMappers.mapUserName(config.userMapper(), parseEqStringValue(comparison))));
      default -> throw new UnsupportedFilterException("Unsupported filter attribute: " + attribute);
    };
  }

  private static String parseEqStringValue(AttributeComparisonExpression comparison)
      throws ResourceException {
    Object rawValue = requireEqValue(comparison);
    if (!(rawValue instanceof String)) {
      throw new ResourceException(400, "Filter compare value must be a non-blank string");
    }
    String value = (String) rawValue;
    if (value.isBlank()) {
      throw new ResourceException(400, "Filter compare value must be a non-blank string");
    }
    return value;
  }

  /**
   * Parses {@code eq} compare values for {@code id}. Salesforce/Okta suites may quote the id as a
   * string or send an unquoted number.
   */
  private static String parseEqScalarValue(AttributeComparisonExpression comparison)
      throws ResourceException {
    Object rawValue = requireEqValue(comparison);
    String value = String.valueOf(rawValue);
    if (value.isBlank() || "null".equals(value)) {
      throw new ResourceException(400, "Filter compare value must be a non-blank string");
    }
    return value;
  }

  private static Object requireEqValue(AttributeComparisonExpression comparison)
      throws ResourceException {
    if (comparison.getOperation() != CompareOperator.EQ) {
      throw new UnsupportedFilterException("Only eq operator is supported");
    }
    Object rawValue = comparison.getCompareValue();
    if (rawValue == null) {
      throw new ResourceException(400, "Filter compare value must be a non-blank string");
    }
    return rawValue;
  }

  private static ScimUserFilter merge(ScimUserFilter left, ScimUserFilter right) {
    return new ScimUserFilter(
        firstPresent(left.id(), right.id()),
        firstPresent(left.externalId(), right.externalId()),
        firstPresent(left.userName(), right.userName()));
  }

  private static Optional<String> firstPresent(Optional<String> first, Optional<String> second) {
    return first.isPresent() ? first : second;
  }
}
