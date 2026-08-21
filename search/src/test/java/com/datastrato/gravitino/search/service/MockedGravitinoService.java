/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.datastrato.gravitino.TestCatalog;
import com.datastrato.gravitino.TestSchema;
import com.datastrato.gravitino.TestTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.util.HashMap;
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
import org.apache.gravitino.authorization.AccessControlDispatcher;
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
import org.apache.gravitino.dto.AuditDTO;
import org.apache.gravitino.dto.rel.ColumnDTO;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.exceptions.NoSuchFunctionException;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.exceptions.NoSuchUserException;
import org.apache.gravitino.exceptions.NoSuchViewException;
import org.apache.gravitino.function.FunctionDefinition;
import org.apache.gravitino.function.FunctionType;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.BaseMetalake;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.ColumnEntity;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.SchemaVersion;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.metalake.MetalakeDispatcher;
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
  public Map<String, FunctionEntity> functions = new HashMap<>();

  private Map<String, Tag> tags = new HashMap<>();
  private Map<String, Set<Tag>> objTags = new HashMap<>();

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
    AccessControlDispatcher accessControlDispatcher = mockAccessControlDispatcher();
    FieldUtils.writeField(gravitinoEnv, "accessControlDispatcher", accessControlDispatcher, true);
    FieldUtils.writeField(
        gravitinoEnv, "internalAccessControlDispatcher", accessControlDispatcher, true);

    return Mockito.spy(service);
  }

  private AccessControlDispatcher mockAccessControlDispatcher() {
    AccessControlDispatcher dispatcher = Mockito.mock(AccessControlDispatcher.class);
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
    UserEntity user =
        UserEntity.builder()
            .withId(entityIdAllocator++)
            .withName(userName)
            .withEnabled(true)
            .withAuditInfo(AuditInfo.EMPTY)
            .withRoleNames(Lists.newArrayList())
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

  private Tag retrieveTag(String name) {
    if (tags.containsKey(name)) {
      return tags.get(name);
    }
    Tag tag =
        TagDTO.builder()
            .withName(name)
            .withComment("test_tag_comment")
            .withAudit(AuditDTO.builder().build())
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
    }
    throw new RuntimeException("No such entity: " + key);
  }
}
