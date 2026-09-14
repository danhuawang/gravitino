/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.tag.DatastratoTagPolicyBatchHelper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.EntityCombinedTable;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;

/** Resolves visible effective tags for all columns in one table. */
final class ColumnTagResolver {

  private final EntityStore entityStore;
  private final DirectTagLoader directTagLoader;

  ColumnTagResolver() {
    this(
        GravitinoEnv.getInstance().entityStore(),
        DatastratoTagPolicyBatchHelper::batchFetchDirectTags);
  }

  ColumnTagResolver(EntityStore entityStore, DirectTagLoader directTagLoader) {
    this.entityStore = Objects.requireNonNull(entityStore);
    this.directTagLoader = Objects.requireNonNull(directTagLoader);
  }

  Map<String, TagDTO[]> resolve(
      String metalake,
      String catalog,
      String schema,
      String tableName,
      Table table,
      UnaryOperator<TagEntity[]> tagFilter) {
    Column[] columns = table.columns();
    if (columns == null || columns.length == 0) {
      return new LinkedHashMap<>();
    }

    MetadataObject tableObject =
        MetadataObjects.of(List.of(catalog, schema, tableName), MetadataObject.Type.TABLE);
    @Nullable
    TableEntity tableEntity =
        tableEntity(NameIdentifierUtil.ofTable(metalake, catalog, schema, tableName), table);
    Map<String, Long> columnIds = columnIds(tableEntity);
    Map<MetadataObject, Optional<Long>> knownEntityIds = new LinkedHashMap<>();
    knownEntityIds.put(tableObject, Optional.ofNullable(tableEntity).map(TableEntity::id));

    List<MetadataObject> columnObjects = new ArrayList<>(columns.length);
    Set<MetadataObject> objectsAndParents = new LinkedHashSet<>();
    for (Column column : columns) {
      MetadataObject columnObject =
          MetadataObjects.of(
              List.of(catalog, schema, tableName, column.name()), MetadataObject.Type.COLUMN);
      columnObjects.add(columnObject);
      knownEntityIds.put(columnObject, Optional.ofNullable(columnIds.get(column.name())));
      objectsAndParents.add(columnObject);
      objectsAndParents.addAll(MetadataObjectUtil.getParentMetadataObjects(columnObject));
    }

    Map<MetadataObject, TagEntity[]> directTags =
        Objects.requireNonNull(
            directTagLoader.load(metalake, new ArrayList<>(objectsAndParents), knownEntityIds));
    Set<String> visibleTagNames = visibleTagNames(directTags, tagFilter);

    Map<String, TagDTO[]> tagsByColumn = new LinkedHashMap<>();
    for (int i = 0; i < columns.length; i++) {
      MetadataObject columnObject = columnObjects.get(i);
      Map<String, TagEntity> effectiveTags = new LinkedHashMap<>();
      addVisibleTags(
          effectiveTags, directTags.getOrDefault(columnObject, new TagEntity[0]), visibleTagNames);
      Set<String> directTagNames = new LinkedHashSet<>(effectiveTags.keySet());
      for (MetadataObject parent : MetadataObjectUtil.getParentMetadataObjects(columnObject)) {
        addVisibleTags(
            effectiveTags, directTags.getOrDefault(parent, new TagEntity[0]), visibleTagNames);
      }
      tagsByColumn.put(
          columns[i].name(),
          effectiveTags.values().stream()
              .map(
                  tag ->
                      DTOConverters.toDTO(tag, Optional.of(!directTagNames.contains(tag.name()))))
              .toArray(TagDTO[]::new));
    }
    return tagsByColumn;
  }

  private static Set<String> visibleTagNames(
      Map<MetadataObject, TagEntity[]> directTags, UnaryOperator<TagEntity[]> tagFilter) {
    Map<String, TagEntity> uniqueTags = new LinkedHashMap<>();
    directTags
        .values()
        .forEach(
            tags -> Arrays.stream(tags).forEach(tag -> uniqueTags.putIfAbsent(tag.name(), tag)));
    TagEntity[] visibleTags =
        Objects.requireNonNull(
            Objects.requireNonNull(tagFilter).apply(uniqueTags.values().toArray(new TagEntity[0])));
    Set<String> visibleTagNames = new LinkedHashSet<>();
    Arrays.stream(visibleTags).forEach(tag -> visibleTagNames.add(tag.name()));
    return visibleTagNames;
  }

  private static void addVisibleTags(
      Map<String, TagEntity> effectiveTags, TagEntity[] tags, Set<String> visibleTagNames) {
    Arrays.stream(tags)
        .filter(tag -> visibleTagNames.contains(tag.name()))
        .forEach(tag -> effectiveTags.putIfAbsent(tag.name(), tag));
  }

  private static Map<String, Long> columnIds(@Nullable TableEntity tableEntity) {
    if (tableEntity == null || tableEntity.columns() == null) {
      return new LinkedHashMap<>();
    }
    Map<String, Long> columnIds = new LinkedHashMap<>();
    tableEntity.columns().forEach(column -> columnIds.put(column.name(), column.id()));
    return columnIds;
  }

  @Nullable
  private TableEntity tableEntity(NameIdentifier identifier, Table table) {
    if (table instanceof EntityCombinedTable) {
      TableEntity storedTable = ((EntityCombinedTable) table).tableFromGravitino();
      if (storedTable != null) {
        return storedTable;
      }
    }

    // Managed-table load results omit the stored entity even though its table and column IDs exist.
    try {
      return entityStore.get(identifier, Entity.EntityType.TABLE, TableEntity.class);
    } catch (NoSuchEntityException e) {
      return null;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load stored table " + identifier, e);
    }
  }

  @FunctionalInterface
  interface DirectTagLoader {

    /**
     * Loads direct tags for the requested metadata objects.
     *
     * @param metalake The metalake name.
     * @param objects The metadata objects and their ancestors.
     * @param knownEntityIds Entity IDs resolved from the loaded table or the entity store.
     * @return Direct tags keyed by metadata object.
     */
    Map<MetadataObject, TagEntity[]> load(
        String metalake,
        List<MetadataObject> objects,
        Map<MetadataObject, Optional<Long>> knownEntityIds);
  }
}
