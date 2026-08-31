/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.authorization.jdbc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.authorization.AuthorizationPrivilege;
import org.apache.gravitino.authorization.AuthorizationSecurableObject;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.authorization.jdbc.JdbcAuthorizationPlugin;
import org.apache.gravitino.authorization.jdbc.JdbcPrivilege;
import org.apache.gravitino.authorization.jdbc.JdbcSecurableObject;
import org.apache.gravitino.exceptions.AuthorizationPluginException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Hive doesn't support the user and group management, so we just return true here. */
public class HiveJdbcAuthorizationPlugin extends JdbcAuthorizationPlugin {
  private static final Logger LOG = LoggerFactory.getLogger(HiveJdbcAuthorizationPlugin.class);
  private static final String SET_ROLE_SQL = "set role admin";

  public HiveJdbcAuthorizationPlugin(Map<String, String> config) {
    super(config);
  }

  @Override
  public Boolean onUserAdded(User user) throws AuthorizationPluginException {
    // Hive doesn't support the user and group management, so we just return true here.
    return true;
  }

  @Override
  public Boolean onUserRemoved(User user) throws AuthorizationPluginException {
    // Hive doesn't support the user and group management, so we just return true here.
    return true;
  }

  @Override
  public Boolean onGroupAdded(Group group) throws AuthorizationPluginException {
    // Hive doesn't support the user and group management, so we just return true here.
    return true;
  }

  @Override
  public Boolean onGroupRemoved(Group group) throws AuthorizationPluginException {
    // Hive doesn't support the user and group management, so we just return true here.
    return true;
  }

  @Override
  public List<String> getSetOwnerSQL(
      MetadataObject.Type type, String objectName, Owner preOwner, Owner newOwner) {
    if (objectName.contains(JdbcSecurableObject.ALL)) {
      return ImmutableList.of();
    }

    if (type == MetadataObject.Type.TABLE) {
      return ImmutableList.of(
          SET_ROLE_SQL,
          String.format("ALTER TABLE %s SET OWNER USER %s", objectName, newOwner.name()));
    } else if (type == MetadataObject.Type.SCHEMA) {
      return ImmutableList.of(
          SET_ROLE_SQL,
          String.format("ALTER DATABASE %s SET OWNER USER %s", objectName, newOwner.name()));
    } else {
      throw new IllegalArgumentException("Unsupported metadata object type");
    }
  }

  @Override
  public List<String> getCreateRoleSQL(String roleName) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    return builder.add(SET_ROLE_SQL).addAll(super.getCreateRoleSQL(roleName)).build();
  }

  @Override
  public List<String> getDropRoleSQL(String roleName) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    return builder.add(SET_ROLE_SQL).addAll(super.getDropRoleSQL(roleName)).build();
  }

  @Override
  public List<String> getGrantPrivilegeSQL(
      String privilege, String objectType, String objectName, String roleName) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    return builder
        .add(SET_ROLE_SQL)
        .addAll(super.getGrantPrivilegeSQL(privilege, objectType, objectName, roleName))
        .build();
  }

  @Override
  public List<String> getRevokePrivilegeSQL(
      String privilege, String objectType, String objectName, String roleName) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    return builder
        .add(SET_ROLE_SQL)
        .addAll(super.getRevokePrivilegeSQL(privilege, objectType, objectName, roleName))
        .build();
  }

  @Override
  public List<String> getGrantRoleSQL(String roleName, String grantorType, String grantorName) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    return builder
        .add(SET_ROLE_SQL)
        .addAll(super.getGrantRoleSQL(roleName, grantorType, grantorName))
        .build();
  }

  @Override
  public List<String> getRevokeRoleSQL(String roleName, String grantorType, String grantorName) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    return builder
        .add(SET_ROLE_SQL)
        .addAll(super.getRevokeRoleSQL(roleName, grantorType, grantorName))
        .build();
  }

  @Override
  protected List<AuthorizationPrivilege> filterUnsupportedPrivileges(
      List<AuthorizationPrivilege> privileges) {
    List<AuthorizationPrivilege> filteredPrivileges = Lists.newArrayList();
    for (AuthorizationPrivilege privilege : privileges) {
      String name = privilege.getName();
      if (!name.equals(JdbcPrivilege.CREATE.name())
          && !name.equals(JdbcPrivilege.DROP.name())
          && !name.equals(JdbcPrivilege.ALTER.name())) {
        filteredPrivileges.add(privilege);
      }
    }
    return filteredPrivileges;
  }

  @Override
  protected List<AuthorizationSecurableObject> convertResourceAll(
      AuthorizationSecurableObject object) {
    if (object.type().metadataObjectType().equals(MetadataObject.Type.TABLE)) {
      if (object.name().equals(JdbcSecurableObject.ALL)
          && object.parent().equals(JdbcSecurableObject.ALL)) {
        List<AuthorizationSecurableObject> objects = Lists.newArrayList();
        for (String database : getAllDatabases()) {
          objects.add(JdbcSecurableObject.create(database, null, object.privileges()));
        }
        return objects;
      } else if (object.name().equals(JdbcSecurableObject.ALL)) {
        AuthorizationSecurableObject newObject =
            JdbcSecurableObject.create(object.parent(), null, object.privileges());
        return Lists.newArrayList(newObject);
      } else {
        throw new IllegalArgumentException(
            String.format(
                "Unsupported metadata object type: %s name: %s",
                object.type().metadataObjectType(), object.fullName()));
      }
    } else if (object.type().metadataObjectType().equals(MetadataObject.Type.SCHEMA)) {
      if (object.name().equals(JdbcSecurableObject.ALL)) {
        List<AuthorizationSecurableObject> objects = Lists.newArrayList();
        for (String database : getAllDatabases()) {
          objects.add(JdbcSecurableObject.create(database, null, object.privileges()));
        }
        return objects;
      } else {
        throw new IllegalArgumentException(
            String.format(
                "Unsupported metadata object type: %s, name: %s",
                object.type().metadataObjectType(), object.fullName()));
      }
    } else {
      throw new IllegalArgumentException(
          String.format("Unsupported metadata object type %s", object.type().metadataObjectType()));
    }
  }

  List<String> getAllDatabases() {
    try (Connection connection = getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SHOW DATABASES")) {
      List<String> databaseNames = Lists.newArrayList();
      while (resultSet.next()) {
        databaseNames.add(resultSet.getString(1));
      }
      return databaseNames;
    } catch (SQLException se) {
      LOG.error("Jdbc authorization plugin exception: ", se);
      throw toAuthorizationPluginException(se);
    }
  }
}
