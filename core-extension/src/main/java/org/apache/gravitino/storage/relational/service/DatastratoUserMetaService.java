/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.po.UserPO;
import org.apache.gravitino.storage.relational.utils.SessionUtils;

/**
 * Enterprise service for batch user_meta mutations.
 *
 * <p>Same role as {@link DatastratoRoleMetaService}: custom / batch SQL that does not fit the
 * EntityStore relation APIs used by {@code listUsersByRole} / {@code updatePrivilegesForRole}.
 */
public class DatastratoUserMetaService {
  private static final DatastratoUserMetaService INSTANCE = new DatastratoUserMetaService();

  private DatastratoUserMetaService() {}

  /**
   * Gets the singleton instance.
   *
   * @return The singleton instance.
   */
  public static DatastratoUserMetaService getInstance() {
    return INSTANCE;
  }

  /**
   * Batch-updates {@code enabled} for users under a metalake.
   *
   * <p>Validates first (every distinct name must exist and must not have an {@code externalId}).
   * Only when validation passes is the UPDATE executed. Validation failure does not run the UPDATE.
   *
   * @param metalake The metalake name.
   * @param usernames User names to update.
   * @param enabled Target enabled value.
   * @return Distinct user names that were updated.
   * @throws IllegalArgumentException If any user is missing or has an external id.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchUpdateUserEnabled")
  public List<String> batchUpdateUserEnabled(
      String metalake, List<String> usernames, boolean enabled) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    Preconditions.checkArgument(
        usernames != null && !usernames.isEmpty(), "usernames cannot be null or empty");

    Set<String> distinctNames = new LinkedHashSet<>();
    for (String username : usernames) {
      Preconditions.checkArgument(StringUtils.isNotBlank(username), "username cannot be blank");
      distinctNames.add(username);
    }
    List<String> names = new ArrayList<>(distinctNames);
    int expectedCount = names.size();

    return SessionUtils.doWithCommitAndFetchResult(
        DatastratoUserMetaMapper.class,
        mapper -> {
          List<UserPO> users = mapper.listUserMetasByMetalakeNameAndNames(metalake, names);
          validateUsersForBatchEnabledUpdate(metalake, names, users);
          int updated = mapper.batchUpdateEnabledByMetalakeNameAndNames(metalake, names, enabled);
          if (updated != expectedCount) {
            // Throw before commit so doWithCommitAndFetchResult rolls back the UPDATE.
            throw new IllegalArgumentException(
                String.format(
                    "Cannot batch update enabled for users under metalake %s: concurrent change"
                        + " detected, expected to update %d users but updated %d",
                    metalake, expectedCount, updated));
          }
          return names;
        });
  }

  private static void validateUsersForBatchEnabledUpdate(
      String metalake, List<String> requestedNames, List<UserPO> users) {
    Set<String> foundNames =
        users.stream()
            .map(UserPO::getUserName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<String> missing =
        requestedNames.stream()
            .filter(name -> !foundNames.contains(name))
            .collect(Collectors.toList());
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "Cannot batch update enabled for users under metalake %s: users do not exist: %s",
              metalake, missing));
    }

    List<String> provisioned =
        users.stream()
            .filter(user -> StringUtils.isNotBlank(user.getExternalId()))
            .map(UserPO::getUserName)
            .collect(Collectors.toList());
    if (!provisioned.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "Cannot batch update enabled for users under metalake %s: users have an externalId:"
                  + " %s",
              metalake, provisioned));
    }
  }
}
