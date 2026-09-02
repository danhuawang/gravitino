/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.authorization.UserWithGroups;
import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import com.datastrato.gravitino.authorization.mapper.IdpNameStatusPO;
import com.datastrato.gravitino.authorization.po.UserWithGroupsPO;
import com.datastrato.gravitino.authorization.utils.DatastratoPOConverters;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.relational.po.UserPO;
import org.apache.gravitino.storage.relational.utils.POConverters;
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
   * Batch-loads users and their direct roles with one SQL statement.
   *
   * @param metalake The metalake name.
   * @param userNames The user names to load.
   * @return The existing users in the requested set.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchGetUsers")
  public List<UserEntity> batchGetUsers(String metalake, List<String> userNames) {
    if (userNames == null || userNames.isEmpty()) {
      return Collections.emptyList();
    }

    Namespace namespace = AuthorizationUtils.ofUserNamespace(metalake);
    List<IdpNameStatusPO.UserWithOrigin> users =
        SessionUtils.getWithoutCommit(
            DatastratoUserMetaMapper.class,
            mapper -> mapper.listUsersByMetalakeAndNamesWithOrigin(metalake, userNames));
    return users.stream()
        .map(user -> POConverters.fromExtendedUserPO(user, namespace))
        .collect(Collectors.toList());
  }

  /**
   * Lists users under a metalake with roles and metalake group names in one SQL query.
   *
   * @param metalake The metalake name.
   * @return Users with group names.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listUsersWithGroups")
  public List<UserWithGroups> listUsersWithGroups(String metalake) {
    Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
    return SessionUtils.getWithoutCommit(
        DatastratoUserMetaMapper.class,
        mapper -> {
          List<UserWithGroupsPO> userPOs = mapper.listUserWithGroupsPOsByMetalakeName(metalake);
          return userPOs.stream()
              .map(
                  po ->
                      DatastratoPOConverters.fromUserWithGroupsPO(
                          po, AuthorizationUtils.ofUserNamespace(metalake)))
              .collect(Collectors.toList());
        });
  }

  /**
   * Batch-updates {@code enabled} for users under a metalake.
   *
   * <p>Validates first (every distinct name must exist). Local users update {@code user_meta};
   * provisioned users (present in {@code scim_user_meta}) update {@code scim_user_meta.enabled}.
   * Validation failure does not run any UPDATE.
   *
   * @param metalake The metalake name.
   * @param usernames User names to update.
   * @param enabled Target enabled value.
   * @return Distinct user names that were updated.
   * @throws IllegalArgumentException If any user is missing.
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

          Set<String> provisionedNames = new HashSet<>(mapper.selectScimUserNamesByNames(names));
          List<String> localNames = new ArrayList<>();
          List<String> scimNames = new ArrayList<>();
          for (String name : names) {
            if (provisionedNames.contains(name)) {
              scimNames.add(name);
            } else {
              localNames.add(name);
            }
          }

          int updated = 0;
          if (!localNames.isEmpty()) {
            updated +=
                mapper.batchUpdateEnabledByMetalakeNameAndNames(metalake, localNames, enabled);
          }
          if (!scimNames.isEmpty()) {
            updated += mapper.batchUpdateScimUserEnabledByUserNames(scimNames, enabled);
          }
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
  }
}
