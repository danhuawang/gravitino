/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.storage.relational.service;

import static org.apache.gravitino.metrics.source.MetricsSource.GRAVITINO_RELATIONAL_STORE_METRIC_NAME;

import com.datastrato.gravitino.authorization.DirectoryUser;
import com.datastrato.gravitino.authorization.UserWithGroups;
import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import com.datastrato.gravitino.authorization.mapper.IdpNameStatusPO;
import com.datastrato.gravitino.authorization.po.DirectoryUserPO;
import com.datastrato.gravitino.authorization.po.IdpGroupIdPO;
import com.datastrato.gravitino.authorization.po.IdpUserGroupRelInsertPO;
import com.datastrato.gravitino.authorization.po.UserWithGroupsPO;
import com.datastrato.gravitino.authorization.utils.DatastratoPOConverters;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.exceptions.AlreadyExistsException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.apache.gravitino.idp.basic.password.PasswordHasherFactory;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.metrics.Monitored;
import org.apache.gravitino.storage.RandomIdGenerator;
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
   * Lists Directory Users for Configure → Directory → Users.
   *
   * <p>Local users come from {@code idp_user_meta}; Provisioned from {@code scim_user_meta}; JIT
   * from distinct {@code user_meta} names absent from both identity stores.
   *
   * @return Directory users ordered by username.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "listDirectoryUsers")
  public List<DirectoryUser> listDirectoryUsers() {
    return SessionUtils.getWithoutCommit(
        DatastratoUserMetaMapper.class,
        mapper -> {
          List<DirectoryUserPO> userPOs = mapper.listDirectoryUsers();
          return userPOs.stream()
              .map(DatastratoPOConverters::fromDirectoryUserPO)
              .collect(Collectors.toList());
        });
  }

  /**
   * Batch-updates {@code enabled} for Local Directory Users in {@code idp_user_meta}.
   *
   * <p>Validates that every entry has {@link IdentitySource#LOCAL} origin and that every username
   * exists in {@code idp_user_meta}. Only then runs a single UPDATE. Validation failure does not
   * update any row.
   *
   * @param names Usernames to update (order preserved; duplicates collapsed).
   * @param origins Identity sources aligned with {@code names} before deduplication; every value
   *     must be Local.
   * @param enabled Target enabled value.
   * @return Distinct usernames that were updated.
   * @throws IllegalArgumentException If any origin is not Local or any username is missing from
   *     {@code idp_user_meta}.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "batchUpdateDirectoryUserEnabled")
  public List<String> batchUpdateDirectoryUserEnabled(
      List<String> names, List<IdentitySource> origins, boolean enabled) {
    Preconditions.checkArgument(names != null && !names.isEmpty(), "names cannot be null or empty");
    Preconditions.checkArgument(origins != null, "origins cannot be null");
    Preconditions.checkArgument(
        names.size() == origins.size(), "names and origins must have the same size");

    LinkedHashSet<String> distinctNames = new LinkedHashSet<>();
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      IdentitySource origin = origins.get(i);
      Preconditions.checkArgument(StringUtils.isNotBlank(name), "username cannot be blank");
      Preconditions.checkArgument(origin != null, "origin cannot be null");
      if (origin != IdentitySource.LOCAL) {
        throw new IllegalArgumentException(
            String.format(
                "Cannot batch update enabled for Directory Users: only Local origin is supported,"
                    + " got %s for user %s",
                origin.value(), name));
      }
      distinctNames.add(name);
    }
    List<String> userNames = new ArrayList<>(distinctNames);
    int expectedCount = userNames.size();

    return SessionUtils.doWithCommitAndFetchResult(
        DatastratoUserMetaMapper.class,
        mapper -> {
          Set<String> foundNames = new HashSet<>(mapper.selectIdpUserNamesByNames(userNames));
          List<String> missing =
              userNames.stream()
                  .filter(name -> !foundNames.contains(name))
                  .collect(Collectors.toList());
          if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                String.format(
                    "Cannot batch update enabled for Directory Users: users do not exist in"
                        + " idp_user_meta: %s",
                    missing));
          }

          int updated = mapper.batchUpdateIdpUserEnabledByUserNames(userNames, enabled);
          if (updated != expectedCount) {
            throw new IllegalArgumentException(
                String.format(
                    "Cannot batch update enabled for Directory Users: concurrent change detected,"
                        + " expected to update %d users but updated %d",
                    expectedCount, updated));
          }
          return userNames;
        });
  }

  /**
   * Creates a Local Directory User in {@code idp_user_meta} and adds membership rows in {@code
   * idp_user_group_rel}.
   *
   * <p>Validates that every group exists in {@code idp_group_meta} and that the username is not
   * already present in {@code idp_user_meta}, then inserts the user and relations in one
   * transaction. The user is created enabled. Password is hashed with the built-in IdP hasher so
   * login remains compatible.
   *
   * @param username Username to create.
   * @param password Plaintext password.
   * @param groupNames Built-in IdP group names to join; {@code null} or empty means none.
   * @return The created Directory User (Local origin, empty metalakes).
   * @throws NotFoundException If any group is missing from {@code idp_group_meta}.
   * @throws AlreadyExistsException If the username already exists in {@code idp_user_meta}.
   */
  @Monitored(
      metricsSource = GRAVITINO_RELATIONAL_STORE_METRIC_NAME,
      baseMetricName = "addDirectoryUser")
  public DirectoryUser addDirectoryUser(String username, String password, List<String> groupNames) {
    Preconditions.checkArgument(StringUtils.isNotBlank(username), "username cannot be blank");
    Preconditions.checkArgument(StringUtils.isNotBlank(password), "password cannot be blank");

    LinkedHashSet<String> distinctGroups = new LinkedHashSet<>();
    if (groupNames != null) {
      for (String groupName : groupNames) {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(groupName), "group name cannot be blank");
        distinctGroups.add(groupName);
      }
    }
    List<String> groups = new ArrayList<>(distinctGroups);
    String passwordHash = PasswordHasherFactory.create().hash(password);
    long userId = RandomIdGenerator.INSTANCE.nextId();

    SessionUtils.doWithCommit(
        DatastratoUserMetaMapper.class,
        mapper -> {
          if (!mapper.selectIdpUserNamesByNames(List.of(username)).isEmpty()) {
            throw new AlreadyExistsException("IdP user already exists: %s", username);
          }

          Map<String, Long> groupIdsByName = new HashMap<>();
          if (!groups.isEmpty()) {
            List<IdpGroupIdPO> foundGroups = mapper.selectIdpGroupIdsByNames(groups);
            for (IdpGroupIdPO row : foundGroups) {
              groupIdsByName.put(row.getGroupName(), row.getGroupId());
            }
            List<String> missing =
                groups.stream()
                    .filter(name -> !groupIdsByName.containsKey(name))
                    .collect(Collectors.toList());
            if (!missing.isEmpty()) {
              throw new NotFoundException("IdP group not found: %s", missing);
            }
          }

          mapper.insertIdpUser(userId, username, passwordHash, true);
          if (!groups.isEmpty()) {
            List<IdpUserGroupRelInsertPO> relations = new ArrayList<>(groups.size());
            for (String groupName : groups) {
              relations.add(
                  new IdpUserGroupRelInsertPO(
                      RandomIdGenerator.INSTANCE.nextId(), userId, groupIdsByName.get(groupName)));
            }
            mapper.batchInsertIdpUserGroupRels(relations);
          }
        });

    return new DirectoryUser(username, true, IdentitySource.LOCAL, groups, List.of());
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
