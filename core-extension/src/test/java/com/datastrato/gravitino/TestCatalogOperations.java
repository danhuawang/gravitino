/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino;

import static org.apache.gravitino.file.Fileset.PROPERTY_DEFAULT_LOCATION_NAME;

import com.datastrato.gravitino.catalog.TestDatastratoOperationDispatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.connector.CatalogInfo;
import org.apache.gravitino.connector.CatalogOperations;
import org.apache.gravitino.connector.HasPropertyMetadata;
import org.apache.gravitino.connector.SupportsSchemas;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.exceptions.FilesetAlreadyExistsException;
import org.apache.gravitino.exceptions.ModelAlreadyExistsException;
import org.apache.gravitino.exceptions.ModelVersionAliasesAlreadyExistException;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchFilesetException;
import org.apache.gravitino.exceptions.NoSuchModelException;
import org.apache.gravitino.exceptions.NoSuchModelVersionException;
import org.apache.gravitino.exceptions.NoSuchModelVersionURINameException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.NoSuchTopicException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.exceptions.TopicAlreadyExistsException;
import org.apache.gravitino.file.Fileset;
import org.apache.gravitino.file.FilesetCatalog;
import org.apache.gravitino.file.FilesetChange;
import org.apache.gravitino.messaging.DataLayout;
import org.apache.gravitino.messaging.Topic;
import org.apache.gravitino.messaging.TopicCatalog;
import org.apache.gravitino.messaging.TopicChange;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.model.Model;
import org.apache.gravitino.model.ModelCatalog;
import org.apache.gravitino.model.ModelChange;
import org.apache.gravitino.model.ModelVersion;
import org.apache.gravitino.model.ModelVersionChange;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;

public class TestCatalogOperations
    implements CatalogOperations,
        TableCatalog,
        FilesetCatalog,
        TopicCatalog,
        SupportsSchemas,
        ModelCatalog {
  private final Map<NameIdentifier, TestTable> tables;

  private final Map<NameIdentifier, TestSchema> schemas;

  private final Map<NameIdentifier, TestFileset> filesets;

  private final Map<NameIdentifier, TestTopic> topics;

  private final Map<NameIdentifier, TestModel> models;

  private final Map<Pair<NameIdentifier, Integer>, TestModelVersion> modelVersions;

  private final Map<Pair<NameIdentifier, String>, Integer> modelAliasToVersion;

  private static final String SLASH = "/";

  public static final String FAIL_CREATE = "fail-create";

  public static final String FAIL_TEST = "need-fail";

  public TestCatalogOperations(Map<String, String> config) {
    tables = Maps.newHashMap();
    schemas = Maps.newHashMap();
    filesets = Maps.newHashMap();
    topics = Maps.newHashMap();
    models = Maps.newHashMap();
    modelVersions = Maps.newHashMap();
    modelAliasToVersion = Maps.newHashMap();
  }

  @Override
  public void initialize(
      Map<String, String> config, CatalogInfo info, HasPropertyMetadata propertyMetadata)
      throws RuntimeException {}

  @Override
  public void close() {}

  @Override
  public NameIdentifier[] listTables(Namespace namespace) throws NoSuchSchemaException {
    return tables.keySet().stream()
        .filter(testTable -> testTable.namespace().equals(namespace))
        .toArray(NameIdentifier[]::new);
  }

  @Override
  public Table loadTable(NameIdentifier ident) throws NoSuchTableException {
    if (tables.containsKey(ident)) {
      return tables.get(ident);
    } else {
      throw new NoSuchTableException("Table %s does not exist", ident);
    }
  }

  @Override
  public Table createTable(
      NameIdentifier ident,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitions,
      Distribution distribution,
      SortOrder[] sortOrders,
      Index[] indexes)
      throws NoSuchSchemaException, TableAlreadyExistsException {
    AuditInfo auditInfo =
        AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build();

    TestTable table =
        TestTable.builder()
            .withName(ident.name())
            .withComment(comment)
            .withProperties(new HashMap<>(properties))
            .withAuditInfo(auditInfo)
            .withColumns(columns)
            .withDistribution(distribution)
            .withSortOrders(sortOrders)
            .withPartitioning(partitions)
            .withIndexes(indexes)
            .build();

    if (tables.containsKey(ident)) {
      throw new TableAlreadyExistsException("Table %s already exists", ident);
    } else {
      tables.put(ident, table);
    }

    return TestTable.builder()
        .withName(ident.name())
        .withComment(comment)
        .withProperties(new HashMap<>(properties))
        .withAuditInfo(auditInfo)
        .withColumns(columns)
        .withDistribution(distribution)
        .withSortOrders(sortOrders)
        .withPartitioning(partitions)
        .withIndexes(indexes)
        .build();
  }

  @Override
  public Table alterTable(NameIdentifier ident, TableChange... changes)
      throws NoSuchTableException, IllegalArgumentException {
    if (!tables.containsKey(ident)) {
      throw new NoSuchTableException("Table %s does not exist", ident);
    }

    AuditInfo updatedAuditInfo =
        AuditInfo.builder()
            .withCreator("test")
            .withCreateTime(Instant.now())
            .withLastModifier("test")
            .withLastModifiedTime(Instant.now())
            .build();

    TestTable table = tables.get(ident);
    Map<String, String> newProps =
        table.properties() != null ? Maps.newHashMap(table.properties()) : Maps.newHashMap();

    NameIdentifier newIdent = ident;
    for (TableChange change : changes) {
      if (change instanceof TableChange.SetProperty) {
        newProps.put(
            ((TableChange.SetProperty) change).getProperty(),
            ((TableChange.SetProperty) change).getValue());
      } else if (change instanceof TableChange.RemoveProperty) {
        newProps.remove(((TableChange.RemoveProperty) change).getProperty());
      } else if (change instanceof TableChange.RenameTable) {
        String newName = ((TableChange.RenameTable) change).getNewName();
        newIdent = NameIdentifier.of(ident.namespace(), newName);
        if (tables.containsKey(newIdent)) {
          throw new TableAlreadyExistsException("Table %s already exists", ident);
        }
      } else {
        throw new IllegalArgumentException("Unsupported table change: " + change);
      }
    }

    TestTable updatedTable =
        TestTable.builder()
            .withName(newIdent.name())
            .withComment(table.comment())
            .withProperties(new HashMap<>(newProps))
            .withAuditInfo(updatedAuditInfo)
            .withColumns(table.columns())
            .withPartitioning(table.partitioning())
            .withDistribution(table.distribution())
            .withSortOrders(table.sortOrder())
            .withIndexes(table.index())
            .build();

    tables.put(ident, updatedTable);
    return updatedTable;
  }

  @Override
  public ModelVersion[] listModelVersionInfos(NameIdentifier ident) throws NoSuchModelException {
    return new ModelVersion[0];
  }

  @Override
  public boolean dropTable(NameIdentifier ident) {
    if (tables.containsKey(ident)) {
      tables.remove(ident);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public NameIdentifier[] listSchemas(Namespace namespace) throws NoSuchCatalogException {
    return schemas.keySet().stream()
        .filter(ident -> ident.namespace().equals(namespace))
        .toArray(NameIdentifier[]::new);
  }

  @Override
  public Schema createSchema(NameIdentifier ident, String comment, Map<String, String> properties)
      throws NoSuchCatalogException, SchemaAlreadyExistsException {
    AuditInfo auditInfo =
        AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build();

    TestSchema schema =
        TestSchema.builder()
            .withName(ident.name())
            .withComment(comment)
            .withProperties(properties)
            .withAuditInfo(auditInfo)
            .build();

    if (schemas.containsKey(ident)) {
      throw new SchemaAlreadyExistsException("Schema %s already exists", ident);
    } else {
      schemas.put(ident, schema);
    }

    return schema;
  }

  @Override
  public Schema loadSchema(NameIdentifier ident) throws NoSuchSchemaException {
    if (schemas.containsKey(ident)) {
      return schemas.get(ident);
    } else {
      throw new NoSuchSchemaException("Schema %s does not exist", ident);
    }
  }

  @Override
  public Schema alterSchema(NameIdentifier ident, SchemaChange... changes)
      throws NoSuchSchemaException {
    if (!schemas.containsKey(ident)) {
      throw new NoSuchSchemaException("Schema %s does not exist", ident);
    }

    AuditInfo updatedAuditInfo =
        AuditInfo.builder()
            .withCreator("test")
            .withCreateTime(Instant.now())
            .withLastModifier("test")
            .withLastModifiedTime(Instant.now())
            .build();

    TestSchema schema = schemas.get(ident);
    Map<String, String> newProps =
        schema.properties() != null ? Maps.newHashMap(schema.properties()) : Maps.newHashMap();

    for (SchemaChange change : changes) {
      if (change instanceof SchemaChange.SetProperty) {
        newProps.put(
            ((SchemaChange.SetProperty) change).getProperty(),
            ((SchemaChange.SetProperty) change).getValue());
      } else if (change instanceof SchemaChange.RemoveProperty) {
        newProps.remove(((SchemaChange.RemoveProperty) change).getProperty());
      } else {
        throw new IllegalArgumentException("Unsupported schema change: " + change);
      }
    }

    TestSchema updatedSchema =
        TestSchema.builder()
            .withName(ident.name())
            .withComment(schema.comment())
            .withProperties(newProps)
            .withAuditInfo(updatedAuditInfo)
            .build();

    schemas.put(ident, updatedSchema);
    return updatedSchema;
  }

  @Override
  public boolean dropSchema(NameIdentifier ident, boolean cascade) throws NonEmptySchemaException {
    if (!schemas.containsKey(ident)) {
      return false;
    }

    schemas.remove(ident);
    if (cascade) {
      tables.keySet().stream()
          .filter(table -> table.namespace().toString().equals(ident.toString()))
          .forEach(tables::remove);
    }

    return true;
  }

  @Override
  public NameIdentifier[] listFilesets(Namespace namespace) throws NoSuchSchemaException {
    return filesets.keySet().stream()
        .filter(ident -> ident.namespace().equals(namespace))
        .toArray(NameIdentifier[]::new);
  }

  @Override
  public Fileset loadFileset(NameIdentifier ident) throws NoSuchFilesetException {
    if (filesets.containsKey(ident)) {
      return filesets.get(ident);
    } else {
      throw new NoSuchFilesetException("Fileset %s does not exist", ident);
    }
  }

  @Override
  public Fileset createMultipleLocationFileset(
      NameIdentifier ident,
      String comment,
      Fileset.Type type,
      Map<String, String> storageLocations,
      Map<String, String> properties)
      throws NoSuchSchemaException, FilesetAlreadyExistsException {
    AuditInfo auditInfo =
        AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build();
    if (storageLocations != null && storageLocations.size() == 1) {
      properties =
          Optional.ofNullable(properties)
              .map(
                  props ->
                      ImmutableMap.<String, String>builder()
                          .putAll(props)
                          .put(
                              PROPERTY_DEFAULT_LOCATION_NAME,
                              storageLocations.keySet().iterator().next())
                          .build())
              .orElseGet(
                  () ->
                      ImmutableMap.of(
                          PROPERTY_DEFAULT_LOCATION_NAME,
                          storageLocations.keySet().iterator().next()));
    }
    TestFileset fileset =
        TestFileset.builder()
            .withName(ident.name())
            .withComment(comment)
            .withProperties(properties)
            .withAuditInfo(auditInfo)
            .withType(type)
            .withStorageLocations(storageLocations)
            .build();

    NameIdentifier schemaIdent = NameIdentifier.of(ident.namespace().levels());
    if (filesets.containsKey(ident)) {
      throw new FilesetAlreadyExistsException("Fileset %s already exists", ident);
    } else if (!schemas.containsKey(schemaIdent)) {
      throw new NoSuchSchemaException("Schema %s does not exist", schemaIdent);
    } else {
      filesets.put(ident, fileset);
    }

    StringIdentifier stringId = StringIdentifier.fromProperties(properties);
    FilesetEntity filesetEntity =
        FilesetEntity.builder()
            .withName(ident.name())
            .withId(stringId.id())
            .withNamespace(ident.namespace())
            .withComment(comment)
            .withFilesetType(type)
            // Store the storageLocation to the store.
            .withStorageLocations(storageLocations)
            // Store the storageLocation to the store. If the "storageLocation" is null,
            // it will be stored as an empty string.
            .withProperties(properties)
            .withAuditInfo(auditInfo)
            .build();
    try {
      TestDatastratoOperationDispatcher.entityStore.put(filesetEntity, true);
    } catch (IOException e) {
      throw new RuntimeException("Failed to store fileset entity", e);
    }

    return fileset;
  }

  @Override
  public Fileset alterFileset(NameIdentifier ident, FilesetChange... changes)
      throws NoSuchFilesetException, IllegalArgumentException {
    if (!filesets.containsKey(ident)) {
      throw new NoSuchFilesetException("Fileset %s does not exist", ident);
    }

    AuditInfo updatedAuditInfo =
        AuditInfo.builder()
            .withCreator("test")
            .withCreateTime(Instant.now())
            .withLastModifier("test")
            .withLastModifiedTime(Instant.now())
            .build();

    TestFileset fileset = filesets.get(ident);
    Map<String, String> newProps =
        fileset.properties() != null ? Maps.newHashMap(fileset.properties()) : Maps.newHashMap();
    NameIdentifier newIdent = ident;
    String newComment = fileset.comment();

    for (FilesetChange change : changes) {
      if (change instanceof FilesetChange.SetProperty) {
        newProps.put(
            ((FilesetChange.SetProperty) change).getProperty(),
            ((FilesetChange.SetProperty) change).getValue());
      } else if (change instanceof FilesetChange.RemoveProperty) {
        newProps.remove(((FilesetChange.RemoveProperty) change).getProperty());
      } else if (change instanceof FilesetChange.RenameFileset) {
        String newName = ((FilesetChange.RenameFileset) change).getNewName();
        newIdent = NameIdentifier.of(ident.namespace(), newName);
        if (filesets.containsKey(newIdent)) {
          throw new FilesetAlreadyExistsException("Fileset %s already exists", ident);
        }
        filesets.remove(ident);
      } else if (change instanceof FilesetChange.UpdateFilesetComment) {
        newComment = ((FilesetChange.UpdateFilesetComment) change).getNewComment();
      } else if (change instanceof FilesetChange.RemoveComment) {
        newComment = null;
      } else {
        throw new IllegalArgumentException("Unsupported fileset change: " + change);
      }
    }

    TestFileset updatedFileset =
        TestFileset.builder()
            .withName(newIdent.name())
            .withComment(newComment)
            .withProperties(newProps)
            .withAuditInfo(updatedAuditInfo)
            .withType(fileset.type())
            .withStorageLocations(fileset.storageLocations())
            .build();
    filesets.put(newIdent, updatedFileset);
    return updatedFileset;
  }

  @Override
  public boolean dropFileset(NameIdentifier ident) {
    if (filesets.containsKey(ident)) {
      filesets.remove(ident);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public String getFileLocation(NameIdentifier ident, String subPath)
      throws NoSuchFilesetException {
    Preconditions.checkArgument(subPath != null, "subPath must not be null");
    String processedSubPath;
    if (!subPath.trim().isEmpty() && !subPath.trim().startsWith(SLASH)) {
      processedSubPath = SLASH + subPath.trim();
    } else {
      processedSubPath = subPath.trim();
    }

    Fileset fileset = loadFileset(ident);

    String fileLocation;
    // subPath cannot be null, so we only need check if it is blank
    if (StringUtils.isBlank(processedSubPath)) {
      fileLocation = fileset.storageLocation();
    } else {
      String storageLocation =
          fileset.storageLocation().endsWith(SLASH)
              ? fileset.storageLocation().substring(0, fileset.storageLocation().length() - 1)
              : fileset.storageLocation();
      fileLocation = String.format("%s%s", storageLocation, processedSubPath);
    }
    return fileLocation;
  }

  @Override
  public NameIdentifier[] listTopics(Namespace namespace) throws NoSuchSchemaException {
    return topics.keySet().stream()
        .filter(ident -> ident.namespace().equals(namespace))
        .toArray(NameIdentifier[]::new);
  }

  @Override
  public Topic loadTopic(NameIdentifier ident) throws NoSuchTopicException {
    if (topics.containsKey(ident)) {
      return topics.get(ident);
    } else {
      throw new NoSuchTopicException("Topic %s does not exist", ident);
    }
  }

  @Override
  public Topic createTopic(
      NameIdentifier ident, String comment, DataLayout dataLayout, Map<String, String> properties)
      throws NoSuchSchemaException, TopicAlreadyExistsException {
    AuditInfo auditInfo =
        AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build();
    TestTopic topic =
        TestTopic.builder()
            .withName(ident.name())
            .withComment(comment)
            .withProperties(properties)
            .withAuditInfo(auditInfo)
            .build();

    if (topics.containsKey(ident)) {
      throw new TopicAlreadyExistsException("Topic %s already exists", ident);
    } else {
      topics.put(ident, topic);
    }

    return topic;
  }

  @Override
  public Topic alterTopic(NameIdentifier ident, TopicChange... changes)
      throws NoSuchTopicException, IllegalArgumentException {
    if (!topics.containsKey(ident)) {
      throw new NoSuchTopicException("Topic %s does not exist", ident);
    }

    AuditInfo updatedAuditInfo =
        AuditInfo.builder()
            .withCreator("test")
            .withCreateTime(Instant.now())
            .withLastModifier("test")
            .withLastModifiedTime(Instant.now())
            .build();

    TestTopic topic = topics.get(ident);
    Map<String, String> newProps =
        topic.properties() != null ? Maps.newHashMap(topic.properties()) : Maps.newHashMap();
    String newComment = topic.comment();

    for (TopicChange change : changes) {
      if (change instanceof TopicChange.SetProperty) {
        newProps.put(
            ((TopicChange.SetProperty) change).getProperty(),
            ((TopicChange.SetProperty) change).getValue());
      } else if (change instanceof TopicChange.RemoveProperty) {
        newProps.remove(((TopicChange.RemoveProperty) change).getProperty());
      } else if (change instanceof TopicChange.UpdateTopicComment) {
        newComment = ((TopicChange.UpdateTopicComment) change).getNewComment();
      } else {
        throw new IllegalArgumentException("Unsupported topic change: " + change);
      }
    }

    TestTopic updatedTopic =
        TestTopic.builder()
            .withName(ident.name())
            .withComment(newComment)
            .withProperties(newProps)
            .withAuditInfo(updatedAuditInfo)
            .build();

    topics.put(ident, updatedTopic);
    return updatedTopic;
  }

  @Override
  public boolean dropTopic(NameIdentifier ident) throws NoSuchTopicException {
    if (topics.containsKey(ident)) {
      topics.remove(ident);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public void testConnection(
      NameIdentifier name,
      Catalog.Type type,
      String provider,
      String comment,
      Map<String, String> properties) {
    if ("true".equals(properties.get(FAIL_TEST))) {
      throw new ConnectionFailedException("Connection failed");
    }
  }

  @Override
  public NameIdentifier[] listModels(Namespace namespace) throws NoSuchSchemaException {
    NameIdentifier modelSchemaIdent = NameIdentifier.of(namespace.levels());
    if (!schemas.containsKey(modelSchemaIdent)) {
      throw new NoSuchSchemaException("Schema %s does not exist", modelSchemaIdent);
    }

    return models.keySet().stream()
        .filter(ident -> ident.namespace().equals(namespace))
        .toArray(NameIdentifier[]::new);
  }

  @Override
  public Model getModel(NameIdentifier ident) throws NoSuchModelException {
    if (models.containsKey(ident)) {
      return models.get(ident);
    } else {
      throw new NoSuchModelException("Model %s does not exist", ident);
    }
  }

  @Override
  public Model registerModel(NameIdentifier ident, String comment, Map<String, String> properties)
      throws NoSuchSchemaException, ModelAlreadyExistsException {
    NameIdentifier schemaIdent = NameIdentifier.of(ident.namespace().levels());
    if (!schemas.containsKey(schemaIdent)) {
      throw new NoSuchSchemaException("Schema %s does not exist", schemaIdent);
    }

    AuditInfo auditInfo =
        AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build();
    TestModel model =
        TestModel.builder()
            .withName(ident.name())
            .withComment(comment)
            .withProperties(properties)
            .withLatestVersion(0)
            .withAuditInfo(auditInfo)
            .build();

    if (models.containsKey(ident)) {
      throw new ModelAlreadyExistsException("Model %s already exists", ident);
    } else {
      models.put(ident, model);
    }

    return model;
  }

  @Override
  public boolean deleteModel(NameIdentifier ident) {
    if (!models.containsKey(ident)) {
      return false;
    }

    models.remove(ident);

    List<Pair<NameIdentifier, Integer>> deletedVersions =
        modelVersions.entrySet().stream()
            .filter(e -> e.getKey().getLeft().equals(ident))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    deletedVersions.forEach(modelVersions::remove);

    List<Pair<NameIdentifier, String>> deletedAliases =
        modelAliasToVersion.entrySet().stream()
            .filter(e -> e.getKey().getLeft().equals(ident))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    deletedAliases.forEach(modelAliasToVersion::remove);

    return true;
  }

  @Override
  public int[] listModelVersions(NameIdentifier ident) throws NoSuchModelException {
    if (!models.containsKey(ident)) {
      throw new NoSuchModelException("Model %s does not exist", ident);
    }

    return modelVersions.entrySet().stream()
        .filter(e -> e.getKey().getLeft().equals(ident))
        .mapToInt(e -> e.getValue().version())
        .toArray();
  }

  @Override
  public ModelVersion getModelVersion(NameIdentifier ident, int version)
      throws NoSuchModelVersionException {
    if (!models.containsKey(ident)) {
      throw new NoSuchModelVersionException("Model %s does not exist", ident);
    }

    Pair<NameIdentifier, Integer> versionPair = Pair.of(ident, version);
    if (!modelVersions.containsKey(versionPair)) {
      throw new NoSuchModelVersionException("Model version %s does not exist", versionPair);
    }

    return modelVersions.get(versionPair);
  }

  @Override
  public ModelVersion getModelVersion(NameIdentifier ident, String alias)
      throws NoSuchModelVersionException {
    if (!models.containsKey(ident)) {
      throw new NoSuchModelVersionException("Model %s does not exist", ident);
    }

    Pair<NameIdentifier, String> aliasPair = Pair.of(ident, alias);
    if (!modelAliasToVersion.containsKey(aliasPair)) {
      throw new NoSuchModelVersionException("Model version %s does not exist", alias);
    }

    int version = modelAliasToVersion.get(aliasPair);
    Pair<NameIdentifier, Integer> versionPair = Pair.of(ident, version);
    if (!modelVersions.containsKey(versionPair)) {
      throw new NoSuchModelVersionException("Model version %s does not exist", versionPair);
    }

    return modelVersions.get(versionPair);
  }

  @Override
  public void linkModelVersion(
      NameIdentifier ident,
      String uri,
      String[] aliases,
      String comment,
      Map<String, String> properties)
      throws NoSuchModelException, ModelVersionAliasesAlreadyExistException {
    if (!models.containsKey(ident)) {
      throw new NoSuchModelException("Model %s does not exist", ident);
    }

    String[] aliasArray = aliases != null ? aliases : new String[0];
    for (String alias : aliasArray) {
      Pair<NameIdentifier, String> aliasPair = Pair.of(ident, alias);
      if (modelAliasToVersion.containsKey(aliasPair)) {
        throw new ModelVersionAliasesAlreadyExistException(
            "Model version alias %s already exists", alias);
      }
    }

    int version = models.get(ident).latestVersion();
    TestModelVersion modelVersion =
        TestModelVersion.builder()
            .withVersion(version)
            .withAliases(aliases)
            .withComment(comment)
            .withUris(ImmutableMap.of("unknown", uri))
            .withProperties(properties)
            .withAuditInfo(
                AuditInfo.builder().withCreator("test").withCreateTime(Instant.now()).build())
            .build();
    Pair<NameIdentifier, Integer> versionPair = Pair.of(ident, version);
    modelVersions.put(versionPair, modelVersion);
    for (String alias : aliasArray) {
      Pair<NameIdentifier, String> aliasPair = Pair.of(ident, alias);
      modelAliasToVersion.put(aliasPair, version);
    }

    TestModel model = models.get(ident);
    TestModel updatedModel =
        TestModel.builder()
            .withName(model.name())
            .withComment(model.comment())
            .withProperties(model.properties())
            .withLatestVersion(version + 1)
            .withAuditInfo(model.auditInfo())
            .build();
    models.put(ident, updatedModel);
  }

  @Override
  public boolean deleteModelVersion(NameIdentifier ident, int version) {
    if (!models.containsKey(ident)) {
      return false;
    }

    Pair<NameIdentifier, Integer> versionPair = Pair.of(ident, version);
    if (!modelVersions.containsKey(versionPair)) {
      return false;
    }

    TestModelVersion modelVersion = modelVersions.remove(versionPair);
    if (modelVersion.aliases() != null) {
      for (String alias : modelVersion.aliases()) {
        Pair<NameIdentifier, String> aliasPair = Pair.of(ident, alias);
        modelAliasToVersion.remove(aliasPair);
      }
    }

    return true;
  }

  @Override
  public boolean deleteModelVersion(NameIdentifier ident, String alias) {
    if (!models.containsKey(ident)) {
      return false;
    }

    Pair<NameIdentifier, String> aliasPair = Pair.of(ident, alias);
    if (!modelAliasToVersion.containsKey(aliasPair)) {
      return false;
    }

    int version = modelAliasToVersion.remove(aliasPair);
    Pair<NameIdentifier, Integer> versionPair = Pair.of(ident, version);
    if (!modelVersions.containsKey(versionPair)) {
      return false;
    }

    TestModelVersion modelVersion = modelVersions.remove(versionPair);
    for (String modelVersionAlias : modelVersion.aliases()) {
      Pair<NameIdentifier, String> modelAliasPair = Pair.of(ident, modelVersionAlias);
      modelAliasToVersion.remove(modelAliasPair);
    }

    return true;
  }

  @Override
  public Model alterModel(NameIdentifier ident, ModelChange... changes)
      throws NoSuchModelException, IllegalArgumentException {
    if (!models.containsKey(ident)) {
      throw new NoSuchModelException("Model %s does not exist", ident);
    }

    TestModel model = models.get(ident);

    String newName = model.name();
    for (ModelChange change : changes) {
      if (change instanceof ModelChange.RenameModel) {
        newName = ((ModelChange.RenameModel) change).newName();
      } else {
        throw new IllegalArgumentException(
            "Unsupported model change: " + change.getClass().getSimpleName());
      }
    }

    TestModel testModel =
        TestModel.builder()
            .withName(newName)
            .withComment(model.comment())
            .withProperties(model.properties())
            .withLatestVersion(model.latestVersion())
            .withAuditInfo(model.auditInfo())
            .build();

    NameIdentifier newIdent = NameIdentifier.of(ident.namespace(), newName);
    try {
      models.put(newIdent, testModel);
      return testModel;
    } catch (NoSuchEntityException nsee) {
      throw new NoSuchModelException(nsee, "Model %s does not exist", ident);
    } catch (EntityAlreadyExistsException eaee) {
      // This is happened when renaming a model to an existing model name.
      throw new RuntimeException("Model already exist " + ident.name(), eaee);
    }
  }

  @Override
  public ModelVersion alterModelVersion(
      NameIdentifier ident, int version, ModelVersionChange... changes)
      throws NoSuchModelException, NoSuchModelVersionException, IllegalArgumentException {
    return null;
  }

  @Override
  public ModelVersion alterModelVersion(
      NameIdentifier ident, String alias, ModelVersionChange... changes)
      throws NoSuchModelException, IllegalArgumentException {
    return null;
  }

  private ModelEntity updateModelEntity(
      NameIdentifier ident, ModelEntity modelEntity, ModelChange... changes) {

    Map<String, String> entityProperties =
        modelEntity.properties() == null
            ? Maps.newHashMap()
            : Maps.newHashMap(modelEntity.properties());
    String entityName = ident.name();
    String entityComment = modelEntity.comment();
    Long entityId = modelEntity.id();
    AuditInfo entityAuditInfo = modelEntity.auditInfo();
    Namespace entityNamespace = modelEntity.namespace();
    Integer entityLatestVersion = modelEntity.latestVersion();

    for (ModelChange change : changes) {
      if (change instanceof ModelChange.RenameModel) {
        entityName = ((ModelChange.RenameModel) change).newName();
      } else {
        throw new IllegalArgumentException(
            "Unsupported model change: " + change.getClass().getSimpleName());
      }
    }

    return ModelEntity.builder()
        .withName(entityName)
        .withId(entityId)
        .withComment(entityComment)
        .withAuditInfo(entityAuditInfo)
        .withNamespace(entityNamespace)
        .withProperties(entityProperties)
        .withLatestVersion(entityLatestVersion)
        .build();
  }

  @Override
  public void linkModelVersion(
      NameIdentifier ident,
      Map<String, String> uris,
      String[] aliases,
      String comment,
      Map<String, String> properties)
      throws NoSuchModelException, ModelVersionAliasesAlreadyExistException {
    linkModelVersion(ident, uris.get("unknown"), aliases, comment, properties);
  }

  @Override
  public String getModelVersionUri(NameIdentifier ident, int version, String uriName)
      throws NoSuchModelVersionException, NoSuchModelVersionURINameException {
    return null;
  }

  @Override
  public String getModelVersionUri(NameIdentifier ident, String alias, String uriName)
      throws NoSuchModelVersionException, NoSuchModelVersionURINameException {
    return null;
  }
}
