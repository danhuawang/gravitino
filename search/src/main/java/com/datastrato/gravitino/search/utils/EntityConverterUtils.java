/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.utils;

import com.datastrato.gravitino.search.po.SearchCatalogEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO.PropertyPO;
import com.datastrato.gravitino.search.po.SearchEntityPO.SearchTagPO;
import com.datastrato.gravitino.search.po.SearchModelEntityPO;
import com.datastrato.gravitino.search.po.SearchModelEntityPO.SearchModelVersionPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.gravitino.Audit;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.catalog.EntityCombinedFileset;
import org.apache.gravitino.catalog.EntityCombinedModel;
import org.apache.gravitino.catalog.EntityCombinedSchema;
import org.apache.gravitino.catalog.EntityCombinedTable;
import org.apache.gravitino.catalog.EntityCombinedTopic;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntityConverterUtils {
  private static final Logger LOG = LoggerFactory.getLogger(EntityConverterUtils.class);

  private EntityConverterUtils() {
    // Prevent instantiation
  }

  @Nullable
  private static String getMetadataObjectOwner(MetadataObject metadataObject, String metalake) {
    OwnerDispatcher ownerDispatcher = GravitinoEnv.getInstance().ownerDispatcher();
    if (ownerDispatcher == null) {
      return null;
    }

    try {
      return ownerDispatcher.getOwner(metalake, metadataObject).map(Owner::name).orElse(null);
    } catch (Exception e) {
      LOG.warn(
          "Failed to get owner for metadata object {} in metalake {}: {}",
          metadataObject,
          metalake,
          e.getMessage());
      return null;
    }
  }

  public static SearchEntityPO toCatalogSearchEntityPO(
      BaseCatalog catalog, Tag[] tags, NameIdentifier nameIdentifier) {
    String inUseString = catalog.entity().getProperties().get("in-use");
    boolean inUse = inUseString == null || Boolean.parseBoolean(inUseString);
    String[] levels = nameIdentifier.namespace().levels();

    String owner =
        getMetadataObjectOwner(
            NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.CATALOG), levels[0]);
    return SearchCatalogEntityPO.SearchCatalogEntityPOBuilder.builder()
        .withEntityId(catalog.entity().id())
        .withEntityType(EntityType.CATALOG)
        .withInUse(inUse)
        .withMetalake(levels[0])
        .withEntityName(catalog.name())
        .withEntityComment(catalog.comment())
        .withCatalogName(nameIdentifier.name())
        .withFullQualifiedName(nameIdentifier.name())
        .withProvider(catalog.provider())
        .withType(catalog.type())
        .withTags(toSearchTag(tags))
        .withSearchAudit(toSearchAudit(catalog.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(null)
        .withRolePermissions(null)
        .withEntityProperties(mapToKeyValueObjects(catalog.properties()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  private static List<PropertyPO> mapToKeyValueObjects(Map<String, String> map) {
    return map.entrySet().stream()
        .map(entry -> new PropertyPO(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());
  }

  private static long getEntityIdFromProperties(
      Map<String, String> properties, EntityType entityType) {
    String identifier = properties.get("gravitino.identifier");
    if (identifier == null) {
      throw new RuntimeException(
          String.format("Can't find %s id in properties: %s", entityType, properties));
    }
    return Long.parseLong(identifier.substring("gravitino.v1.uid".length()));
  }

  public static SearchEntityPO toSchemaSearchEntityPO(
      EntityCombinedSchema schema, Tag[] tags, NameIdentifier nameIdentifier) {
    String inUseString = schema.properties().get("in-use");
    boolean inUse = inUseString == null || Boolean.parseBoolean(inUseString);
    SchemaEntity schemaEntity = schema.schemaEntity();
    long id =
        schemaEntity != null
            ? schemaEntity.id()
            : getEntityIdFromProperties(schema.schema().properties(), EntityType.SCHEMA);

    String metalakeName = nameIdentifier.namespace().levels()[0];
    String catalog = nameIdentifier.namespace().levels()[1];
    String owner =
        getMetadataObjectOwner(
            NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.SCHEMA), metalakeName);
    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(id)
        .withEntityType(EntityType.SCHEMA)
        .withInUse(inUse)
        .withMetalake(metalakeName)
        .withEntityName(schema.name())
        .withEntityComment(schema.comment())
        .withCatalogName(catalog)
        .withFullQualifiedName(String.format("%s.%s", catalog, schema.name()))
        .withTags(toSearchTag(tags))
        .withSearchAudit(toSearchAudit(schema.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(null)
        .withRolePermissions(null)
        .withEntityProperties(mapToKeyValueObjects(schema.properties()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  public static SearchEntityPO toTopicSearchEntityPO(
      EntityCombinedTopic topic, Tag[] tags, NameIdentifier nameIdentifier) {
    String inUseString = topic.properties().get("in-use");
    boolean inUse = inUseString == null || Boolean.parseBoolean(inUseString);
    TopicEntity topicEntity = topic.topicEntity();

    long id =
        topicEntity != null
            ? topicEntity.id()
            : getEntityIdFromProperties(topic.topic().properties(), EntityType.TOPIC);

    String metalakeName = nameIdentifier.namespace().levels()[0];
    String catalog = nameIdentifier.namespace().levels()[1];
    String schema = nameIdentifier.namespace().levels()[2];

    String owner =
        getMetadataObjectOwner(
            NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.TOPIC), metalakeName);
    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(id)
        .withEntityType(EntityType.TOPIC)
        .withInUse(inUse)
        .withMetalake(metalakeName)
        .withEntityName(topic.name())
        .withEntityComment(topic.comment())
        .withCatalogName(catalog)
        .withFullQualifiedName(String.format("%s.%s.%s", catalog, schema, topic.name()))
        .withTags(toSearchTag(tags))
        .withSearchAudit(toSearchAudit(topic.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(null)
        .withRolePermissions(null)
        .withEntityProperties(mapToKeyValueObjects(topic.properties()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  public static SearchEntityPO toModelSearchEntityPO(
      EntityCombinedModel model,
      Tag[] tags,
      NameIdentifier nameIdentifier,
      List<SearchModelVersionPO> searchModelVersionPOS) {
    String inUseString = model.properties().get("in-use");
    boolean inUse = inUseString == null || Boolean.parseBoolean(inUseString);
    ModelEntity modelEntity = model.modelEntity();
    long id =
        modelEntity != null
            ? modelEntity.id()
            : getEntityIdFromProperties(model.model().properties(), EntityType.MODEL);

    String metalakeName = nameIdentifier.namespace().levels()[0];
    String catalog = nameIdentifier.namespace().levels()[1];
    String schema = nameIdentifier.namespace().levels()[2];
    String owner =
        getMetadataObjectOwner(
            NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.MODEL), metalakeName);

    return SearchModelEntityPO.SearchModelEntityPOBuilder.builder()
        .withEntityId(id)
        .withEntityType(EntityType.MODEL)
        .withInUse(inUse)
        .withMetalake(metalakeName)
        .withEntityName(model.name())
        .withEntityComment(model.comment())
        .withCatalogName(catalog)
        .withFullQualifiedName(String.format("%s.%s.%s", catalog, schema, model.name()))
        .withTags(toSearchTag(tags))
        .withSearchAudit(toSearchAudit(model.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(null)
        .withRolePermissions(null)
        .withEntityProperties(mapToKeyValueObjects(model.properties()))
        .withUpdateTime(System.currentTimeMillis())
        .withModelVersions(searchModelVersionPOS)
        .withLatestVersion(model.latestVersion())
        .build();
  }

  public static SearchEntityPO toFilesetSearchEntityPO(
      EntityCombinedFileset fileset, Tag[] tags, NameIdentifier nameIdentifier) {
    String inUseString = fileset.properties().get("in-use");
    boolean inUse = inUseString == null || Boolean.parseBoolean(inUseString);
    FilesetEntity filesetEntity = fileset.filesetEntity();

    long id =
        filesetEntity != null
            ? filesetEntity.id()
            : getEntityIdFromProperties(fileset.fileset().properties(), EntityType.FILESET);

    String metalakeName = nameIdentifier.namespace().levels()[0];
    String catalog = nameIdentifier.namespace().levels()[1];
    String schema = nameIdentifier.namespace().levels()[2];
    String owner =
        getMetadataObjectOwner(
            NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.FILESET), metalakeName);

    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(filesetEntity != null ? filesetEntity.id() : id)
        .withEntityType(EntityType.FILESET)
        .withInUse(inUse)
        .withMetalake(metalakeName)
        .withEntityName(fileset.name())
        .withEntityComment(fileset.comment())
        .withCatalogName(catalog)
        .withFullQualifiedName(String.format("%s.%s.%s", catalog, schema, fileset.name()))
        .withTags(toSearchTag(tags))
        .withSearchAudit(toSearchAudit(fileset.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(null)
        .withRolePermissions(null)
        .withEntityProperties(mapToKeyValueObjects(fileset.properties()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  public static SearchTableEntityPO toTableSearchEntityPO(
      EntityCombinedTable table, Tag[] tags, NameIdentifier nameIdentifier) {
    String inUseString = table.properties().get("in-use");
    boolean inUse = inUseString == null || Boolean.parseBoolean(inUseString);
    TableEntity tableEntity = table.tableEntity();

    long id =
        tableEntity != null
            ? tableEntity.id()
            : getEntityIdFromProperties(table.tableFromCatalog().properties(), EntityType.TABLE);

    String metalakeName = nameIdentifier.namespace().levels()[0];
    String catalog = nameIdentifier.namespace().levels()[1];
    String schema = nameIdentifier.namespace().levels()[2];
    String owner =
        getMetadataObjectOwner(
            NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.TABLE), metalakeName);

    return SearchTableEntityPO.SearchTableEntityPOBuilder.builder()
        .withEntityId(id)
        .withEntityType(EntityType.TABLE)
        .withInUse(inUse)
        .withMetalake(metalakeName)
        .withEntityName(table.name())
        .withEntityComment(table.comment())
        .withCatalogName(catalog)
        .withFullQualifiedName(String.format("%s.%s.%s", catalog, schema, table.name()))
        .withTags(toSearchTag(tags))
        .withSearchAudit(toSearchAudit(table.auditInfo()))
        .withColumns(
            Arrays.stream(table.columns())
                .map(
                    cl ->
                        SearchTableEntityPO.SearchColumn.builder()
                            .withColumnName(cl.name())
                            .withColumnComment(cl.comment())
                            .build())
                .collect(Collectors.toList()))
        .withOwner(owner)
        .withUserPermissions(null)
        .withRolePermissions(null)
        .withEntityProperties(mapToKeyValueObjects(table.properties()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  private static List<SearchTagPO> toSearchTag(Tag[] tags) {
    if (ArrayUtils.isEmpty(tags)) {
      return Collections.emptyList();
    }

    return Arrays.stream(tags)
        .map(
            tag ->
                SearchEntityPO.SearchTagPO.builder()
                    .withTagName(tag.name())
                    .withTagComment(tag.comment())
                    .withProperties(tag.properties())
                    .build())
        .collect(Collectors.toList());
  }

  private static SearchEntityPO.SearchAuditPO toSearchAudit(Audit audit) {
    if (audit == null) {
      return null;
    }
    return SearchEntityPO.SearchAuditPO.builder()
        .withCreateTime(
            audit.createTime() == null
                ? null
                : LocalDateTime.ofInstant(audit.createTime(), ZoneId.systemDefault()))
        .withCreator(audit.creator())
        .withLastModifiedTime(
            audit.lastModifiedTime() == null
                ? null
                : LocalDateTime.ofInstant(audit.lastModifiedTime(), ZoneId.systemDefault()))
        .withLastModifier(audit.lastModifier())
        .build();
  }
}
