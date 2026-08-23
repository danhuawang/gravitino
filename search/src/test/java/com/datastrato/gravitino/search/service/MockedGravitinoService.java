/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.datastrato.gravitino.DatastratoGravitinoEnv;
import com.datastrato.gravitino.TestCatalog;
import com.datastrato.gravitino.TestSchema;
import com.datastrato.gravitino.TestTable;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.EntityCombinedSchema;
import org.apache.gravitino.catalog.EntityCombinedTable;
import org.apache.gravitino.catalog.EntityCombinedView;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.exceptions.NoSuchFunctionException;
import org.apache.gravitino.exceptions.NoSuchGroupException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchRoleException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.exceptions.NoSuchViewException;
import org.apache.gravitino.function.FunctionDefinition;
import org.apache.gravitino.function.FunctionType;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.ColumnEntity;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.GroupEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.RoleEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Representation;
import org.apache.gravitino.rel.SQLRepresentation;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.mockito.Mockito;

public class MockedGravitinoService {
  private Map<String, BaseMetalake> metalakes = new HashMap<>();
  public Map<String, BaseCatalog> catalogs = new HashMap<>();
  public Map<String, EntityCombinedSchema> schemas = new HashMap<>();
  public Map<String, EntityCombinedTable> tables = new HashMap<>();
  public Map<String, EntityCombinedView> views = new HashMap<>();
  public Map<String, UserEntity> users = new HashMap<>();
  public Map<String, GroupEntity> groups = new HashMap<>();
  public Map<String, FunctionEntity> functions = new HashMap<>();
  public Map<String, RoleEntity> roles = new HashMap<>();

  private Map<String, Tag> tags = new HashMap<>();
  private Map<String, Set<Tag>> objTags = new HashMap<>();
  private Map<String, PolicyEntity> policies = new HashMap<>();
  private Map<String, Set<PolicyEntity>> objPolicies = new HashMap<>();

  private long entityIdAllocator = 0;

  public SearchService createMokedSearchService(SearchService service)
      throws IllegalAccessException {
    GravitinoEnv gravitinoEnv = GravitinoEnv.getInstance();
    FieldUtils.writeField(gravitinoEnv, "metalakeDispatcher", mockMetalakeDispatcher(), true);
    FieldUtils.writeField(gravitinoEnv, "catalogDispatcher", mockCatalogDispatcher(), true);
    FieldUtils.writeField(gravitinoEnv, "schemaDispatcher", mockSchemaDispatcher(), true);
    FieldUtils.writeField(gravitinoEnv, "tableDispatcher", mockTableDispatcher(), true);
    FieldUtils.writeField(gravitinoEnv, "viewDispatcher", mockViewDispatcher(), true);
    FieldUtils.writeField(gravitinoEnv, "functionDispatcher", mockFunctionDispatcher(), true);
    FieldUtils.writeField(gravitinoEnv, "tagDispatcher", mockTagDispatcher(), true);
    DatastratoAccessControlDispatcher accessControlDispatcher = mockAccessControlDispatcher();
    FieldUtils.writeField(gravitinoEnv, "accessControlDispatcher", accessControlDispatcher, true);
    FieldUtils.writeField(
        gravitinoEnv, "internalAccessControlDispatcher", accessControlDispatcher, true);
    FieldUtils.writeField(
        DatastratoGravitinoEnv.getInstance(),
        "accessControlDispatcher",
        accessControlDispatcher,
        true);
    FieldUtils.writeField(
        DatastratoGravitinoEnv.getInstance(),
        "internalDatastratoAccessControlDispatcher",
        accessControlDispatcher,
        true);
    FieldUtils.writeField(gravitinoEnv, "policyDispatcher", mockPolicyDispatcher(), true);

    return Mockito.spy(service);
  }

  private DatastratoAccessControlDispatcher mockAccessControlDispatcher() {
    DatastratoAccessControlDispatcher dispatcher =
        Mockito.mock(DatastratoAccessControlDispatcher.class);
    Mockito.when(dispatcher.listUserNames(anyString()))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              return users.values().stream()
                  .filter(user -> metalake.equals(user.namespace().level(0)))
                  .map(User::name)
                  .toArray(String[]::new);
            });
    Mockito.when(dispatcher.listUsers(anyString()))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              return users.values().stream()
                  .filter(user -> metalake.equals(user.namespace().level(0)))
                  .toArray(User[]::new);
            });
    Mockito.when(dispatcher.getUser(anyString(), anyString()))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              String userName = args.getArgument(1);
              User user = users.get(NameIdentifierUtil.ofUser(metalake, userName).toString());
              if (user == null) {
                throw new NoSuchUserException("No such user: %s", userName);
              }
              return user;
            });
    Mockito.when(dispatcher.listGroupNames(anyString()))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              return groups.values().stream()
                  .filter(group -> metalake.equals(group.namespace().level(0)))
                  .map(Group::name)
                  .toArray(String[]::new);
            });
    Mockito.when(dispatcher.listGroups(anyString()))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              return groups.values().stream()
                  .filter(group -> metalake.equals(group.namespace().level(0)))
                  .toArray(Group[]::new);
            });
    Mockito.when(dispatcher.getGroup(anyString(), anyString()))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              String groupName = args.getArgument(1);
              Group group = groups.get(NameIdentifierUtil.ofGroup(metalake, groupName).toString());
              if (group == null) {
                throw new NoSuchGroupException("No such group: %s", groupName);
              }
              return group;
            });
    Mockito.when(dispatcher.listRoleNames(anyString()))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              return roles.values().stream()
                  .filter(role -> metalake.equals(role.namespace().level(0)))
                  .map(Role::name)
                  .toArray(String[]::new);
            });
    Mockito.when(dispatcher.getRole(anyString(), anyString()))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              String roleName = args.getArgument(1);
              Role role = roles.get(NameIdentifierUtil.ofRole(metalake, roleName).toString());
              if (role == null) {
                throw new NoSuchRoleException("No such role: %s", roleName);
              }
              return role;
            });
    Mockito.when(dispatcher.listUsersByRole(anyString(), anyString()))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              String roleName = args.getArgument(1);
              return users.values().stream()
                  .filter(user -> metalake.equals(user.namespace().level(0)))
                  .filter(user -> user.roles() != null && user.roles().contains(roleName))
                  .toArray(User[]::new);
            });
    return dispatcher;
  }

  private MetalakeDispatcher mockMetalakeDispatcher() {
    MetalakeDispatcher metalakeDispatcher = Mockito.mock(MetalakeDispatcher.class);

    Mockito.when(metalakeDispatcher.listMetalakes())
        .thenReturn(metalakes.values().toArray(new Metalake[0]));
    Mockito.when(metalakeDispatcher.loadMetalake(any(NameIdentifier.class)))
        .thenAnswer(
            x -> {
              String name = x.getArgument(0);
              if (metalakes.containsKey(name)) {
                return metalakes.get(name);
              } else {
                throw new NoSuchMetalakeException("No such metalake: %s", name);
              }
            });
    Mockito.when(metalakeDispatcher.metalakeExists(any(NameIdentifier.class)))
        .thenAnswer(
            args -> {
              NameIdentifier nameIdentifier = args.getArgument(0);
              return metalakes.containsKey(nameIdentifier.name());
            });
    return metalakeDispatcher;
  }

  private CatalogDispatcher mockCatalogDispatcher() {
    CatalogDispatcher catalogDispatcher = Mockito.mock(CatalogDispatcher.class);

    Mockito.when(catalogDispatcher.listCatalogs(any(Namespace.class)))
        .thenAnswer(
            args -> {
              Namespace namespace = args.getArgument(0);
              return catalogs.keySet().stream()
                  .filter(c -> c.startsWith(namespace.toString() + "."))
                  .map(NameIdentifier::parse)
                  .toArray(NameIdentifier[]::new);
            });
    Mockito.when(catalogDispatcher.catalogExists(any(NameIdentifier.class)))
        .thenAnswer(
            args -> {
              NameIdentifier name = args.getArgument(0);
              return catalogs.containsKey(name.toString());
            });
    Mockito.when(catalogDispatcher.loadCatalog(any(NameIdentifier.class)))
        .thenAnswer(
            args -> {
              NameIdentifier name = args.getArgument(0);
              if (catalogs.containsKey(name.toString())) {
                return catalogs.get(name.toString());
              } else {
                throw new NoSuchMetalakeException("No such catalog: %s", name);
              }
            });

    return catalogDispatcher;
  }

  private SchemaDispatcher mockSchemaDispatcher() {
    SchemaDispatcher schemaDispatcher = Mockito.mock(SchemaDispatcher.class);

    Mockito.when(schemaDispatcher.listSchemas(any(Namespace.class)))
        .thenAnswer(
            args -> {
              Namespace namespace = args.getArgument(0);
              return schemas.keySet().stream()
                  .filter(s -> s.startsWith(namespace.toString() + "."))
                  .map(NameIdentifier::parse)
                  .toArray(NameIdentifier[]::new);
            });
    Mockito.when(schemaDispatcher.schemaExists(any(NameIdentifier.class)))
        .thenAnswer(
            args -> {
              NameIdentifier name = args.getArgument(0);
              return schemas.containsKey(name.toString());
            });
    Mockito.when(schemaDispatcher.loadSchema(any(NameIdentifier.class)))
        .thenAnswer(
            args -> {
              NameIdentifier name = args.getArgument(0);
              if (schemas.containsKey(name.toString())) {
                return schemas.get(name.toString());
              } else {
                throw new NoSuchMetalakeException("No such schema: %s", name);
              }
            });
    return schemaDispatcher;
  }

  private TableDispatcher mockTableDispatcher() {
    TableDispatcher tableDispatcher = Mockito.mock(TableDispatcher.class);

    Mockito.when(tableDispatcher.listTables(any(Namespace.class)))
        .thenAnswer(
            args -> {
              Namespace namespace = args.getArgument(0);
              return tables.keySet().stream()
                  .filter(t -> t.startsWith(namespace.toString() + "."))
                  .map(NameIdentifier::parse)
                  .toArray(NameIdentifier[]::new);
            });
    Mockito.when(tableDispatcher.tableExists(any(NameIdentifier.class)))
        .thenAnswer(
            args -> {
              NameIdentifier name = args.getArgument(0);
              return tables.containsKey(name.toString());
            });
    Mockito.when(tableDispatcher.loadTable(any(NameIdentifier.class)))
        .thenAnswer(
            args -> {
              NameIdentifier name = args.getArgument(0);
              if (tables.containsKey(name.toString())) {
                return tables.get(name.toString());
              } else {
                throw new NoSuchMetalakeException("No such table: %s", name);
              }
            });
    return tableDispatcher;
  }

  private ViewDispatcher mockViewDispatcher() {
    ViewDispatcher viewDispatcher = Mockito.mock(ViewDispatcher.class);

    Mockito.when(viewDispatcher.listViews(any(Namespace.class)))
        .thenAnswer(
            args -> {
              Namespace namespace = args.getArgument(0);
              return views.keySet().stream()
                  .filter(v -> v.startsWith(namespace.toString() + "."))
                  .map(NameIdentifier::parse)
                  .toArray(NameIdentifier[]::new);
            });
    Mockito.when(viewDispatcher.viewExists(any(NameIdentifier.class)))
        .thenAnswer(
            args -> {
              NameIdentifier name = args.getArgument(0);
              return views.containsKey(name.toString());
            });
    Mockito.when(viewDispatcher.loadView(any(NameIdentifier.class)))
        .thenAnswer(
            args -> {
              NameIdentifier name = args.getArgument(0);
              if (views.containsKey(name.toString())) {
                return views.get(name.toString());
              } else {
                throw new NoSuchViewException("No such view: %s", name);
              }
            });
    return viewDispatcher;
  }

  private FunctionDispatcher mockFunctionDispatcher() {
    FunctionDispatcher functionDispatcher = Mockito.mock(FunctionDispatcher.class);

    Mockito.when(functionDispatcher.listFunctions(any(Namespace.class)))
        .thenAnswer(
            args -> {
              Namespace namespace = args.getArgument(0);
              return functions.keySet().stream()
                  .filter(function -> function.startsWith(namespace.toString() + "."))
                  .map(NameIdentifier::parse)
                  .toArray(NameIdentifier[]::new);
            });
    Mockito.when(functionDispatcher.functionExists(any(NameIdentifier.class)))
        .thenAnswer(args -> functions.containsKey(args.getArgument(0).toString()));
    Mockito.when(functionDispatcher.getFunction(any(NameIdentifier.class)))
        .thenAnswer(
            args -> {
              NameIdentifier name = args.getArgument(0);
              FunctionEntity function = functions.get(name.toString());
              if (function == null) {
                throw new NoSuchFunctionException("No such function: %s", name);
              }
              return function;
            });

    return functionDispatcher;
  }

  private TagDispatcher mockTagDispatcher() {
    TagDispatcher tagDispatcher = Mockito.mock(TagDispatcher.class);
    Mockito.when(tagDispatcher.listTags(anyString()))
        .thenAnswer(args -> tags.keySet().toArray(new String[0]));
    Mockito.when(tagDispatcher.listTagsInfo(anyString()))
        .thenAnswer(args -> tags.values().toArray(new Tag[0]));
    Mockito.when(tagDispatcher.getTag(anyString(), anyString()))
        .thenAnswer(args -> retrieveTag(args.getArgument(1)));
    Mockito.when(
            tagDispatcher.listTagsInfoForMetadataObject(anyString(), any(MetadataObject.class)))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              MetadataObject obj = args.getArgument(1);
              String nameKey = metalake + "." + obj.fullName();
              if (objTags.containsKey(nameKey)) {
                return objTags.get(nameKey).toArray(new Tag[0]);
              }
              return null;
            });

    Mockito.when(tagDispatcher.listMetadataObjectsForTag(anyString(), anyString()))
        .thenAnswer(
            args -> {
              String tagName = args.getArgument(1);
              return objTags.entrySet().stream()
                  .filter(
                      entry -> entry.getValue().stream().anyMatch(t -> t.name().equals(tagName)))
                  .map(
                      entry -> {
                        // This is a simple mock, so we assume the metadata object is just metalake,
                        // catalog, schema, and table
                        String[] parts = entry.getKey().split("\\.");
                        MetadataObject.Type type;
                        switch (parts.length) {
                          case 1:
                            type = MetadataObject.Type.CATALOG;
                            break;
                          case 2:
                            type = MetadataObject.Type.CATALOG;
                            break;
                          case 3:
                            type = MetadataObject.Type.SCHEMA;
                            break;
                          case 4:
                            type = MetadataObject.Type.TABLE;
                            break;
                          default:
                            throw new IllegalArgumentException(
                                "Unexpected metadata object key: " + entry.getKey());
                        }

                        String fullName = parts[0];
                        if (parts.length > 1) {
                          parts = ArrayUtils.remove(parts, 0); // Remove metalake part
                          fullName = String.join(".", parts);
                        }

                        return MetadataObjects.parse(fullName, type);
                      })
                  .toArray(MetadataObject[]::new);
            });

    return tagDispatcher;
  }

  private PolicyDispatcher mockPolicyDispatcher() {
    PolicyDispatcher policyDispatcher = Mockito.mock(PolicyDispatcher.class);
    Mockito.when(policyDispatcher.listPolicies(anyString()))
        .thenAnswer(args -> policies.keySet().toArray(new String[0]));
    Mockito.when(policyDispatcher.listPolicyInfos(anyString()))
        .thenAnswer(args -> policies.values().toArray(new PolicyEntity[0]));
    Mockito.when(policyDispatcher.getPolicy(anyString(), anyString()))
        .thenAnswer(args -> policies.get(args.getArgument(1)));
    Mockito.when(
            policyDispatcher.listPolicyInfosForMetadataObject(
                anyString(), any(MetadataObject.class)))
        .thenAnswer(
            args -> {
              String metalake = args.getArgument(0);
              MetadataObject object = args.getArgument(1);
              return objPolicies
                  .getOrDefault(metalake + "." + object.fullName(), ImmutableSet.of())
                  .toArray(new PolicyEntity[0]);
            });
    Mockito.when(policyDispatcher.listMetadataObjectsForPolicy(anyString(), anyString()))
        .thenAnswer(
            args -> {
              String policyName = args.getArgument(1);
              return objPolicies.entrySet().stream()
                  .filter(
                      entry ->
                          entry.getValue().stream()
                              .anyMatch(policy -> policy.name().equals(policyName)))
                  .map(entry -> toMetadataObject(entry.getKey()))
                  .toArray(MetadataObject[]::new);
            });
    return policyDispatcher;
  }

  private static MetadataObject toMetadataObject(String key) {
    String[] parts = key.split("\\.");
    MetadataObject.Type type;
    switch (parts.length) {
      case 2:
        type = MetadataObject.Type.CATALOG;
        break;
      case 3:
        type = MetadataObject.Type.SCHEMA;
        break;
      case 4:
        type = MetadataObject.Type.TABLE;
        break;
      default:
        throw new IllegalArgumentException("Unexpected metadata object key: " + key);
    }
    return MetadataObjects.parse(String.join(".", ArrayUtils.remove(parts, 0)), type);
  }

  public BaseMetalake createMetalake(String name) {
    BaseMetalake metalake =
        BaseMetalake.builder()
            .withName(name)
            .withVersion(SchemaVersion.V_0_1)
            .withAuditInfo(AuditInfo.EMPTY)
            .withId(entityIdAllocator++)
            .build();

    metalakes.put(name, metalake);
    return metalake;
  }

  /** Adds a User to the in-memory authorization dispatcher used by search tests. */
  public UserEntity createUser(String metalake, String userName) {
    return createUser(metalake, userName, Lists.newArrayList());
  }

  /**
   * Adds a User with directly assigned Roles to the authorization dispatcher used by search tests.
   *
   * @param metalake The User's Metalake.
   * @param userName The User name.
   * @param roleNames The directly assigned Role names.
   * @return The created User.
   */
  public UserEntity createUser(String metalake, String userName, List<String> roleNames) {
    UserEntity user =
        UserEntity.builder()
            .withId(entityIdAllocator++)
            .withName(userName)
            .withEnabled(true)
            .withAuditInfo(AuditInfo.EMPTY)
            .withRoleNames(roleNames)
            .withRoleIds(Lists.newArrayList())
            .withNamespace(NamespaceUtil.ofUser(metalake))
            .build();
    users.put(NameIdentifierUtil.ofUser(metalake, userName).toString(), user);
    return user;
  }

  /** Removes a User from the in-memory authorization dispatcher used by search tests. */
  public void removeUser(String metalake, String userName) {
    users.remove(NameIdentifierUtil.ofUser(metalake, userName).toString());
  }

  /** Adds a Group to the in-memory authorization dispatcher used by search tests. */
  public GroupEntity createGroup(String metalake, String groupName) {
    GroupEntity group =
        GroupEntity.builder()
            .withId(entityIdAllocator++)
            .withName(groupName)
            .withAuditInfo(AuditInfo.EMPTY)
            .withRoleNames(Lists.newArrayList())
            .withRoleIds(Lists.newArrayList())
            .withNamespace(NamespaceUtil.ofGroup(metalake))
            .build();
    groups.put(NameIdentifierUtil.ofGroup(metalake, groupName).toString(), group);
    return group;
  }

  /** Removes a Group from the in-memory authorization dispatcher used by search tests. */
  public void removeGroup(String metalake, String groupName) {
    groups.remove(NameIdentifierUtil.ofGroup(metalake, groupName).toString());
  }

  /** Adds a Role to the in-memory authorization dispatcher used by search tests. */
  public RoleEntity createRole(
      String metalake, String roleName, List<SecurableObject> securableObjects) {
    RoleEntity role =
        RoleEntity.builder()
            .withId(entityIdAllocator++)
            .withName(roleName)
            .withProperties(ImmutableMap.of())
            .withAuditInfo(AuditInfo.EMPTY)
            .withSecurableObjects(securableObjects)
            .withNamespace(NamespaceUtil.ofRole(metalake))
            .build();
    roles.put(NameIdentifierUtil.ofRole(metalake, roleName).toString(), role);
    return role;
  }

  /** Replaces the Securable Objects of a Role used by search tests. */
  public RoleEntity updateRoleSecurableObjects(
      String metalake, String roleName, List<SecurableObject> securableObjects) {
    RoleEntity current = roles.get(NameIdentifierUtil.ofRole(metalake, roleName).toString());
    RoleEntity updated =
        RoleEntity.builder()
            .withId(current.id())
            .withName(current.name())
            .withProperties(current.properties())
            .withAuditInfo(current.auditInfo())
            .withSecurableObjects(securableObjects)
            .withNamespace(current.namespace())
            .build();
    roles.put(NameIdentifierUtil.ofRole(metalake, roleName).toString(), updated);
    return updated;
  }

  /** Removes a Role from the in-memory authorization dispatcher used by search tests. */
  public void removeRole(String metalake, String roleName) {
    roles.remove(NameIdentifierUtil.ofRole(metalake, roleName).toString());
  }

  public BaseCatalog createCatalog(NameIdentifier nameIdentifier) throws IllegalAccessException {
    BaseCatalog baseCatalog = new TestCatalog();
    FieldUtils.writeField(
        baseCatalog,
        "entity",
        CatalogEntity.builder()
            .withName(nameIdentifier.name())
            .withAuditInfo(AuditInfo.EMPTY)
            .withId(entityIdAllocator++)
            .withProvider("jdbc-mysql")
            .withType(Catalog.Type.RELATIONAL)
            .withProperties(ImmutableMap.of())
            .build(),
        true);
    catalogs.put(nameIdentifier.toString(), baseCatalog);
    return baseCatalog;
  }

  public EntityCombinedSchema createSchema(NameIdentifier nameIdentifier) {
    EntityCombinedSchema schema =
        EntityCombinedSchema.of(
            TestSchema.builder()
                .withName(nameIdentifier.name())
                .withAuditInfo(AuditInfo.EMPTY)
                .withProperties(ImmutableMap.of())
                .withComment("test schema")
                .build(),
            SchemaEntity.builder()
                .withName(nameIdentifier.name())
                .withAuditInfo(AuditInfo.EMPTY)
                .withId(entityIdAllocator++)
                .withComment("test schema")
                .withProperties(ImmutableMap.of())
                .build());
    schemas.put(nameIdentifier.toString(), schema);
    return schema;
  }

  public EntityCombinedTable createTable(NameIdentifier nameIdentifier) {
    EntityCombinedTable table =
        EntityCombinedTable.of(
            TestTable.builder()
                .withName(nameIdentifier.name())
                .withProperties(ImmutableMap.of())
                .withComment("test_metalake.test_catalog1.test_schema1.test_table2")
                .withAuditInfo(AuditInfo.EMPTY)
                .withColumns(
                    new Column[] {
                      ColumnDTO.builder()
                          .withName("test_column")
                          .withDataType(Types.IntegerType.get())
                          .build()
                    })
                .build(),
            TableEntity.builder()
                .withName(nameIdentifier.name())
                .withId(entityIdAllocator++)
                .withColumns(
                    Lists.newArrayList(
                        ColumnEntity.builder()
                            .withName(nameIdentifier.name())
                            .withDataType(Types.IntegerType.get())
                            .withAuditInfo(AuditInfo.EMPTY)
                            .withId(entityIdAllocator++)
                            .withPosition(0)
                            .build()))
                .withAuditInfo(AuditInfo.EMPTY)
                .build());
    tables.put(nameIdentifier.toString(), table);
    return table;
  }

  public EntityCombinedView createView(NameIdentifier nameIdentifier) {
    ViewEntity view =
        ViewEntity.builder()
            .withId(entityIdAllocator++)
            .withName(nameIdentifier.name())
            .withNamespace(nameIdentifier.namespace())
            .withComment("test view")
            .withColumns(
                new Column[] {
                  ColumnDTO.builder()
                      .withName("test_view_column")
                      .withDataType(Types.IntegerType.get())
                      .withComment("the view column")
                      .build()
                })
            .withRepresentations(
                new Representation[] {
                  SQLRepresentation.builder().withDialect("trino").withSql("SELECT 1").build()
                })
            .withProperties(ImmutableMap.of("refresh-mode", "incremental"))
            .withAuditInfo(AuditInfo.EMPTY)
            .build();

    EntityCombinedView combinedView = EntityCombinedView.of(view, view);
    views.put(nameIdentifier.toString(), combinedView);
    return combinedView;
  }

  public FunctionEntity createFunction(NameIdentifier nameIdentifier) {
    return putFunction(nameIdentifier, "test function");
  }

  public FunctionEntity putFunction(NameIdentifier nameIdentifier, String comment) {
    FunctionEntity oldFunction = functions.get(nameIdentifier.toString());
    long entityId = oldFunction == null ? entityIdAllocator++ : oldFunction.id();
    FunctionEntity function =
        FunctionEntity.builder()
            .withId(entityId)
            .withName(nameIdentifier.name())
            .withNamespace(nameIdentifier.namespace())
            .withComment(comment)
            .withFunctionType(FunctionType.SCALAR)
            .withDeterministic(true)
            .withDefinitions(new FunctionDefinition[0])
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    functions.put(nameIdentifier.toString(), function);
    return function;
  }

  public void addTagsToObject(NameIdentifier nameIdentifier, Set<String> tags) {
    objTags.put(
        nameIdentifier.toString(),
        tags.stream().map(this::retrieveTag).collect(Collectors.toSet()));
  }

  /**
   * Creates or replaces a tag, the way creating or altering it server side would.
   *
   * @param name The tag name.
   * @param comment The tag comment.
   * @param properties The tag properties.
   * @return The stored tag.
   */
  public Tag putTag(String name, String comment, Map<String, String> properties) {
    Tag existing = tags.get(name);
    Tag tag =
        TagEntity.builder()
            .withId(existing == null ? entityIdAllocator++ : ((TagEntity) existing).id())
            .withName(name)
            .withNamespace(Namespace.of("test_metalake"))
            .withComment(comment)
            .withAuditInfo(AuditInfo.EMPTY)
            .withProperties(properties)
            .build();
    tags.put(name, tag);
    return tag;
  }

  /**
   * Renames a tag while preserving its entity ID and metadata-object associations.
   *
   * @param oldName The current tag name.
   * @param newName The new tag name.
   * @param comment The updated tag comment.
   * @param properties The updated tag properties.
   * @return The renamed tag.
   */
  public Tag renameTag(
      String oldName, String newName, String comment, Map<String, String> properties) {
    Tag existing = tags.remove(oldName);
    if (existing == null) {
      throw new IllegalArgumentException("No such tag: " + oldName);
    }

    TagEntity existingEntity = (TagEntity) existing;
    Tag renamed =
        TagEntity.builder()
            .withId(existingEntity.id())
            .withName(newName)
            .withNamespace(existingEntity.namespace())
            .withComment(comment)
            .withAuditInfo(existing.auditInfo())
            .withProperties(properties)
            .build();
    tags.put(newName, renamed);
    objTags.replaceAll(
        (ignored, associatedTags) ->
            associatedTags.stream()
                .map(tag -> oldName.equals(tag.name()) ? renamed : tag)
                .collect(Collectors.toSet()));
    return renamed;
  }

  PolicyEntity createPolicy(String name) {
    PolicyEntity policy =
        PolicyEntity.builder()
            .withId(entityIdAllocator++)
            .withName(name)
            .withNamespace(Namespace.of("test_metalake"))
            .withPolicyType(Policy.BuiltInType.CUSTOM)
            .withComment("test_policy_comment")
            .withEnabled(true)
            .withContent(
                PolicyContents.custom(
                    ImmutableMap.of("retentionDays", 30),
                    ImmutableSet.of(MetadataObject.Type.TABLE),
                    ImmutableMap.of("owner", "governance")))
            .withAuditInfo(AuditInfo.EMPTY)
            .build();
    policies.put(name, policy);
    return policy;
  }

  /** Drops a policy from Gravitino, the way deleting it server side would. */
  void deletePolicy(String name) {
    policies.remove(name);
    objPolicies
        .values()
        .forEach(associated -> associated.removeIf(policy -> policy.name().equals(name)));
  }

  void addPoliciesToObject(NameIdentifier nameIdentifier, Set<String> policyNames) {
    objPolicies.put(
        nameIdentifier.toString(),
        policyNames.stream().map(policies::get).collect(Collectors.toSet()));
  }

  private Tag retrieveTag(String name) {
    if (tags.containsKey(name)) {
      return tags.get(name);
    }
    Tag tag =
        TagEntity.builder()
            .withId(entityIdAllocator++)
            .withName(name)
            .withNamespace(Namespace.of("test_metalake"))
            .withComment("test_tag_comment")
            .withAuditInfo(AuditInfo.EMPTY)
            .withProperties(ImmutableMap.of("key", "value"))
            .build();
    tags.put(name, tag);
    return tag;
  }

  public long getEntityId(NameIdentifier nameIdentifier) {
    String key = nameIdentifier.toString();
    if (tables.containsKey(key)) {
      return tables.get(key).tableFromGravitino().id();
    } else if (schemas.containsKey(key)) {
      return schemas.get(key).schemaEntity().id();
    } else if (views.containsKey(key)) {
      return views.get(key).viewEntity().id();
    } else if (functions.containsKey(key)) {
      return functions.get(key).id();
    } else if (catalogs.containsKey(key)) {
      return catalogs.get(key).entity().id();
    } else if (tags.containsKey(nameIdentifier.name())) {
      return ((TagEntity) tags.get(nameIdentifier.name())).id();
    } else if (policies.containsKey(nameIdentifier.name())) {
      return policies.get(nameIdentifier.name()).id();
    }
    throw new RuntimeException("No such entity: " + key);
  }
}
