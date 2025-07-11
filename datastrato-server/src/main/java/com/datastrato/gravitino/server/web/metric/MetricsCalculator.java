/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.metric;

import com.google.common.collect.ImmutableMap;
import com.sun.security.auth.UserPrincipal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsCalculator {
  public static final String[] EMPTY_STRING_ARRAY = new String[0];

  private static final Logger LOG = LoggerFactory.getLogger(MetricsCalculator.class);
  private static final Set<MetadataObject.Type> assetTypes =
      new HashSet<MetadataObject.Type>() {
        {
          add(MetadataObject.Type.CATALOG);
          add(MetadataObject.Type.SCHEMA);
          add(MetadataObject.Type.TABLE);
          add(MetadataObject.Type.FILESET);
          add(MetadataObject.Type.TOPIC);
          add(MetadataObject.Type.MODEL);
        }
      };

  private final SchemaDispatcher schemaDispatcher;
  private final TableDispatcher tableDispatcher;
  private final FilesetDispatcher filesetDispatcher;
  private final TopicDispatcher topicDispatcher;
  private final ModelDispatcher modelDispatcher;
  private final TagDispatcher tagDispatcher;
  private final Map<Catalog.Type, AssetCountCalculator> assetCalculators =
      ImmutableMap.of(
          Catalog.Type.RELATIONAL, this::getTableCountFromSchemas,
          Catalog.Type.FILESET, this::getFilesetCountFromSchemas,
          Catalog.Type.MESSAGING, this::getTopicCountFromSchemas,
          Catalog.Type.MODEL, this::getModelCountFromSchemas);

  public MetricsCalculator(
      SchemaDispatcher schemaDispatcher,
      TableDispatcher tableDispatcher,
      FilesetDispatcher filesetDispatcher,
      TopicDispatcher topicDispatcher,
      ModelDispatcher modelDispatcher,
      TagDispatcher tagDispatcher) {
    this.schemaDispatcher = schemaDispatcher;
    this.tableDispatcher = tableDispatcher;
    this.filesetDispatcher = filesetDispatcher;
    this.topicDispatcher = topicDispatcher;
    this.modelDispatcher = modelDispatcher;
    this.tagDispatcher = tagDispatcher;
  }

  @FunctionalInterface
  private interface AssetCountCalculator {
    long calculate(
        UserPrincipal principal, String metalakeName, String catalogName, String[] schemaNames);
  }

  @Data
  @AllArgsConstructor
  public static class AssetCounts {
    private final long schemaCount;
    private final long tableCount;
    private final long filesetCount;
    private final long topicCount;
    private final long modelCount;

    public long getTotalAssetCount(long catalogCount) {
      return catalogCount + schemaCount + tableCount + filesetCount + topicCount + modelCount;
    }
  }

  public AssetCounts calculateAssetCountsByCatalogs(
      UserPrincipal principal, String metalake, Catalog[] catalogs) throws Exception {
    long schemaCount = 0;
    Map<Catalog.Type, Long> objectCounter =
        new HashMap<Catalog.Type, Long>() {
          {
            put(Catalog.Type.RELATIONAL, 0L);
            put(Catalog.Type.FILESET, 0L);
            put(Catalog.Type.MESSAGING, 0L);
            put(Catalog.Type.MODEL, 0L);
          }
        };

    for (Catalog catalog : catalogs) {
      String[] schemaNames = getSchemaNames(principal, metalake, catalog.name());
      schemaCount += schemaNames.length;

      AssetCountCalculator calculator = assetCalculators.get(catalog.type());
      if (calculator != null) {
        long count = calculator.calculate(principal, metalake, catalog.name(), schemaNames);
        objectCounter.computeIfPresent(catalog.type(), (k, v) -> v + count);
      }
    }

    return new AssetCounts(
        schemaCount,
        objectCounter.get(Catalog.Type.RELATIONAL),
        objectCounter.get(Catalog.Type.FILESET),
        objectCounter.get(Catalog.Type.MESSAGING),
        objectCounter.get(Catalog.Type.MODEL));
  }

  public AssetCounts calculateAssetCountsBySchemas(
      UserPrincipal principal, String metalake, Map<Catalog, List<String>> catalogOfSchemas) {
    long tableCount = 0;
    long filesetCount = 0;
    long topicCount = 0;
    long modelCount = 0;

    for (Map.Entry<Catalog, List<String>> entry : catalogOfSchemas.entrySet()) {
      Catalog catalog = entry.getKey();
      List<String> schemaNames = entry.getValue();

      MetricsCalculator.AssetCountCalculator calculator = assetCalculators.get(catalog.type());
      if (calculator != null) {
        long count =
            calculator.calculate(
                principal, metalake, catalog.name(), schemaNames.toArray(EMPTY_STRING_ARRAY));
        switch (catalog.type()) {
          case RELATIONAL:
            tableCount += count;
            break;
          case FILESET:
            filesetCount += count;
            break;
          case MESSAGING:
            topicCount += count;
            break;
          case MODEL:
            modelCount += count;
            break;
          default:
            throw new IllegalStateException("Unexpected catalog type: " + catalog.type());
        }
      }
    }
    return new AssetCounts(0, tableCount, filesetCount, topicCount, modelCount);
  }

  public long getTaggedAssetCount(
      String metalakeName, UserPrincipal principal, Catalog[] catalogs, String[] tagNames)
      throws Exception {
    if (tagNames == null || tagNames.length == 0) {
      return 0;
    }

    Map<MetadataObject.Type, Set<MetadataObject>> taggedObjects =
        Arrays.stream(tagNames)
            // todo: using batch API to get metadata objects for tags
            .flatMap(
                t -> {
                  try {
                    return Arrays.stream(listMetadataObjectsForTag(principal, metalakeName, t));
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                })
            .filter(o -> assetTypes.contains(o.type()))
            .collect(Collectors.groupingBy(MetadataObject::type, Collectors.toSet()));
    removeOverlappingTaggedObjects(taggedObjects);

    Set<String> taggedCatalogNames =
        taggedObjects.get(MetadataObject.Type.CATALOG).stream()
            .map(MetadataObject::name)
            .collect(Collectors.toSet());
    Catalog[] taggedCatalogs =
        Arrays.stream(catalogs)
            .filter(c -> taggedCatalogNames.contains(c.name()))
            .toArray(Catalog[]::new);

    long taggedCatalogCount = taggedCatalogs.length;
    long taggedSchemaCount = taggedObjects.get(MetadataObject.Type.SCHEMA).size();
    long taggedTableCount = taggedObjects.get(MetadataObject.Type.TABLE).size();
    long taggedFilesetCount = taggedObjects.get(MetadataObject.Type.FILESET).size();
    long taggedTopicCount = taggedObjects.get(MetadataObject.Type.TOPIC).size();
    long taggedModelCount = taggedObjects.get(MetadataObject.Type.MODEL).size();

    // accumulate tagged asset counts under tagged catalogs
    MetricsCalculator.AssetCounts taggedAssetCounts =
        calculateAssetCountsByCatalogs(principal, metalakeName, taggedCatalogs);
    taggedSchemaCount += taggedAssetCounts.getSchemaCount();
    taggedTableCount += taggedAssetCounts.getTableCount();
    taggedFilesetCount += taggedAssetCounts.getFilesetCount();
    taggedTopicCount += taggedAssetCounts.getTopicCount();
    taggedModelCount += taggedAssetCounts.getModelCount();

    // accumulate tagged asset counts under tagged schemas
    Map<Catalog, List<String>> catalogOfSchemas =
        taggedObjects.get(MetadataObject.Type.SCHEMA).stream()
            .filter(
                s -> {
                  if (Arrays.stream(catalogs).noneMatch(c -> c.name().equals(s.parent()))) {
                    LOG.warn(
                        "Catalog for tagged schema {} in metalake {} not found, "
                            + "skipping tagged asset count accumulation",
                        s.name(),
                        metalakeName);
                    return false;
                  }
                  return true;
                })
            .collect(
                Collectors.groupingBy(
                    s ->
                        Arrays.stream(catalogs)
                            .filter(c -> c.name().equals(s.parent()))
                            .findFirst()
                            // must be present due to the filter above
                            .get(),
                    Collectors.mapping(MetadataObject::name, Collectors.toList())));

    AssetCounts taggedAssetCountsUnderSchemas =
        calculateAssetCountsBySchemas(principal, metalakeName, catalogOfSchemas);
    taggedTableCount += taggedAssetCountsUnderSchemas.getTableCount();
    taggedFilesetCount += taggedAssetCountsUnderSchemas.getFilesetCount();
    taggedTopicCount += taggedAssetCountsUnderSchemas.getTopicCount();
    taggedModelCount += taggedAssetCountsUnderSchemas.getModelCount();

    return taggedCatalogCount
        + taggedSchemaCount
        + taggedTableCount
        + taggedFilesetCount
        + taggedTopicCount
        + taggedModelCount;
  }

  private String[] getSchemaNames(UserPrincipal principal, String metalakeName, String catalogName)
      throws Exception {
    return PrincipalUtils.doAs(
        principal,
        () ->
            Arrays.stream(
                    schemaDispatcher.listSchemas(NamespaceUtil.ofSchema(metalakeName, catalogName)))
                .map(NameIdentifier::name)
                .toArray(String[]::new));
  }

  private MetadataObject[] listMetadataObjectsForTag(
      UserPrincipal principal, String metalakeName, String tagName) throws Exception {
    return PrincipalUtils.doAs(
        principal, () -> tagDispatcher.listMetadataObjectsForTag(metalakeName, tagName));
  }

  private long getTableCountFromSchemas(
      UserPrincipal principal, String metalakeName, String catalogName, String[] schemaNames) {
    return Arrays.stream(schemaNames)
        .mapToLong(
            s -> {
              try {
                return getTableCountFromSchema(principal, metalakeName, catalogName, s);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .sum();
  }

  private long getTableCountFromSchema(
      UserPrincipal principal, String metalakeName, String catalogName, String schemaName)
      throws Exception {
    return PrincipalUtils.doAs(
        principal,
        () ->
            tableDispatcher.listTables(NamespaceUtil.ofTable(metalakeName, catalogName, schemaName))
                .length);
  }

  private long getFilesetCountFromSchemas(
      UserPrincipal principal, String metalakeName, String catalogName, String[] schemaNames) {
    return Arrays.stream(schemaNames)
        .mapToLong(
            s -> {
              try {
                return getFilesetCountFromSchema(principal, metalakeName, catalogName, s);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .sum();
  }

  private long getTopicCountFromSchemas(
      UserPrincipal principal, String metalakeName, String catalogName, String[] schemaNames) {
    return Arrays.stream(schemaNames)
        .mapToLong(
            s -> {
              try {
                return getTopicCountFromSchema(principal, metalakeName, catalogName, s);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .sum();
  }

  private long getModelCountFromSchemas(
      UserPrincipal principal, String metalakeName, String catalogName, String[] schemaNames) {
    return Arrays.stream(schemaNames)
        .mapToLong(
            s -> {
              try {
                return getModelCountFromSchema(principal, metalakeName, catalogName, s);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            })
        .sum();
  }

  private long getModelCountFromSchema(
      UserPrincipal principal, String metalakeName, String catalogName, String schemaName)
      throws Exception {
    return PrincipalUtils.doAs(
        principal,
        () ->
            modelDispatcher.listModels(NamespaceUtil.ofModel(metalakeName, catalogName, schemaName))
                .length);
  }

  private long getTopicCountFromSchema(
      UserPrincipal principal, String metalakeName, String catalogName, String schemaName)
      throws Exception {
    return PrincipalUtils.doAs(
        principal,
        () ->
            topicDispatcher.listTopics(NamespaceUtil.ofTopic(metalakeName, catalogName, schemaName))
                .length);
  }

  private long getFilesetCountFromSchema(
      UserPrincipal principal, String metalakeName, String catalogName, String schemaName)
      throws Exception {
    return PrincipalUtils.doAs(
        principal,
        () ->
            filesetDispatcher.listFilesets(
                    NamespaceUtil.ofFileset(metalakeName, catalogName, schemaName))
                .length);
  }

  private void removeOverlappingTaggedObjects(
      Map<MetadataObject.Type, Set<MetadataObject>> taggedObjects) {
    Set<MetadataObject> taggedCatalogObjects =
        taggedObjects.computeIfAbsent(MetadataObject.Type.CATALOG, t -> Collections.emptySet());

    // remove schemas that are already covered by catalogs
    Set<MetadataObject> taggedSchemaObjects =
        taggedObjects.computeIfAbsent(MetadataObject.Type.SCHEMA, t -> Collections.emptySet());
    Set<String> taggedCatalogPrefix =
        taggedCatalogObjects.stream().map(o -> o.fullName() + ".").collect(Collectors.toSet());
    taggedSchemaObjects.removeIf(
        schemaObject -> taggedCatalogPrefix.contains(schemaObject.parent() + "."));

    // remove tables that are already covered by catalogs and schemas
    Set<MetadataObject> taggedTableObjects =
        taggedObjects.computeIfAbsent(MetadataObject.Type.TABLE, t -> Collections.emptySet());
    Set<String> taggedSchemaPrefix =
        taggedSchemaObjects.stream().map(o -> o.fullName() + ".").collect(Collectors.toSet());
    taggedTableObjects.removeIf(
        tableObject -> {
          String fullTableName = tableObject.fullName();
          return taggedCatalogPrefix.stream().anyMatch(fullTableName::startsWith)
              || taggedSchemaPrefix.stream().anyMatch(fullTableName::startsWith);
        });

    // remove filesets that are already covered by catalogs and schemas
    Set<MetadataObject> taggedFilesetObjects =
        taggedObjects.computeIfAbsent(MetadataObject.Type.FILESET, t -> Collections.emptySet());
    taggedFilesetObjects.removeIf(
        filesetObject -> {
          String fullFilesetName = filesetObject.fullName();
          return taggedCatalogPrefix.stream().anyMatch(fullFilesetName::startsWith)
              || taggedSchemaPrefix.stream().anyMatch(fullFilesetName::startsWith);
        });

    // remove topics that are already covered by catalogs and schemas
    Set<MetadataObject> taggedTopicObjects =
        taggedObjects.computeIfAbsent(MetadataObject.Type.TOPIC, t -> Collections.emptySet());
    taggedTopicObjects.removeIf(
        topicObject -> {
          String fullTopicName = topicObject.fullName();
          return taggedCatalogPrefix.stream().anyMatch(fullTopicName::startsWith)
              || taggedSchemaPrefix.stream().anyMatch(fullTopicName::startsWith);
        });

    // remove models that are already covered by catalogs and schemas
    Set<MetadataObject> taggedModelObjects =
        taggedObjects.computeIfAbsent(MetadataObject.Type.MODEL, t -> Collections.emptySet());
    taggedModelObjects.removeIf(
        modelObject -> {
          String fullModelName = modelObject.fullName();
          return taggedCatalogPrefix.stream().anyMatch(fullModelName::startsWith)
              || taggedSchemaPrefix.stream().anyMatch(fullModelName::startsWith);
        });
  }
}
