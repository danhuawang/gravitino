/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.NamespaceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interface representing a source of search entities.
 *
 * <p>Defines methods to retrieve entity batches, check completion status, and access failed
 * entities.
 *
 * <pre>
 * Hierarchy of entity sources: MetalakeSearchEntitySource (RootSearchEntitySource)
 * ｜-- CatalogSearchEntitySource (ParentSearchEntitySource)
 * ｜ ｜-- SchemaEntitySource (ParentSearchEntitySource)
 * ｜ ｜ ｜-- TableEntitySource (LeafSearchEntitySource)
 * ｜ ｜ ｜-- ViewEntitySource (LeafSearchEntitySource)
 * ｜ ｜ ｜-- FunctionEntitySource (LeafSearchEntitySource)
 * ｜ ｜ ｜-- TopicEntitySource (LeafSearchEntitySource)
 * ｜ ｜ ｜-- FilesetEntitySource (LeafSearchEntitySource)
 * ｜ ｜ ｜-- ModelEntitySource (LeafSearchEntitySource)
 * </pre>
 */
interface SearchEntitySource {

  Logger LOG = LoggerFactory.getLogger(SearchEntitySource.class);

  /**
   * Returns the next batch of entities to be processed.
   *
   * @param batchSize the size of the batch to be returned
   * @return a list of entities to be processed
   */
  List<SearchEntityPO> nextBatch(int batchSize);

  /**
   * Returns the approximate count of entities is used to calculate the number of sync tasks
   *
   * @return the approximate total number of entities
   */
  int approximateEntityCount();

  /** Returns the source is finished processing. */
  boolean finished();

  /** Returns the source are failed to process. */
  List<SearchEntityIdentifier> getProcessFailedEntities();

  /**
   * Creates a SearchEntitySource based on the given SearchEntityMetadata and cascade flag.
   *
   * @param searchEntityIdentifier the metadata of the search entity
   * @param cascade whether to cascade the search
   * @return a SearchEntitySource instance
   */
  static SearchEntitySource createSearchEntitySource(
      SearchEntityIdentifier searchEntityIdentifier, boolean cascade) {
    switch (searchEntityIdentifier.entityType()) {
      case METALAKE:
        return new MetalakeSearchEntitySource(searchEntityIdentifier, cascade);
      case CATALOG:
        return new CatalogSearchEntitySource(searchEntityIdentifier, cascade);
      case SCHEMA:
        NameIdentifier catalogIdentifier =
            NameIdentifierUtil.getCatalogIdentifier(searchEntityIdentifier.entityIdent());
        Catalog.Type catalogType = CatalogSearchEntitySource.getCatalogType(catalogIdentifier);
        return new SchemaSearchEntitySource(searchEntityIdentifier, cascade, catalogType);
      case TABLE:
        return new TableSearchEntitySource(ImmutableList.of(searchEntityIdentifier));
      case VIEW:
        return new ViewSearchEntitySource(ImmutableList.of(searchEntityIdentifier));
      case FUNCTION:
        return new FunctionSearchEntitySource(ImmutableList.of(searchEntityIdentifier));
      case TOPIC:
        return new TopicSearchEntitySource(ImmutableList.of(searchEntityIdentifier));
      case FILESET:
        return new FilesetSearchEntitySource(ImmutableList.of(searchEntityIdentifier));
      case MODEL:
        return new ModelSearchEntitySource(ImmutableList.of(searchEntityIdentifier));
      case TAG:
        return new TagSearchEntitySource(ImmutableList.of(searchEntityIdentifier));
      case USER:
        return new UserSearchEntitySource(ImmutableList.of(searchEntityIdentifier));
      case GROUP:
        return new GroupSearchEntitySource(ImmutableList.of(searchEntityIdentifier));
      case ROLE:
        return new RoleSearchEntitySource(ImmutableList.of(searchEntityIdentifier));
      case POLICY:
        return new PolicySearchEntitySource(ImmutableList.of(searchEntityIdentifier));

      default:
        throw new GravitinoRuntimeException(
            "Unsupported entity type: " + searchEntityIdentifier.entityType());
    }
  }

  /**
   * Create the SearchEntitySources holding the entities directly under the given schema.
   *
   * @param schemaNameIdentifier the name identifier of the schema
   * @param catalogType the type of the catalog
   * @return the SearchEntitySource instances of the schema children
   */
  static List<SearchEntitySource> createSearchEntitySourceBySchema(
      NameIdentifier schemaNameIdentifier, Catalog.Type catalogType) {
    Namespace ns = Namespace.fromString(schemaNameIdentifier.toString());
    NamespaceUtil.checkTable(ns);

    NameIdentifier[] nameIdentifiers;

    switch (catalogType) {
      case RELATIONAL:
        nameIdentifiers = GravitinoEnv.getInstance().tableDispatcher().listTables(ns);
        ImmutableList.Builder<SearchEntitySource> relationalSources = ImmutableList.builder();
        relationalSources.add(
            new TableSearchEntitySource(
                toSearchEntityIdentifiers(nameIdentifiers, Entity.EntityType.TABLE)));
        listViews(ns)
            .map(
                idents ->
                    new ViewSearchEntitySource(
                        toSearchEntityIdentifiers(idents, Entity.EntityType.VIEW)))
            .ifPresent(relationalSources::add);
        listFunctions(ns)
            .map(
                idents ->
                    new FunctionSearchEntitySource(
                        toSearchEntityIdentifiers(idents, Entity.EntityType.FUNCTION)))
            .ifPresent(relationalSources::add);
        return relationalSources.build();

      case MESSAGING:
        nameIdentifiers = GravitinoEnv.getInstance().topicDispatcher().listTopics(ns);
        return ImmutableList.of(
            new TopicSearchEntitySource(
                toSearchEntityIdentifiers(nameIdentifiers, Entity.EntityType.TOPIC)));

      case FILESET:
        nameIdentifiers = GravitinoEnv.getInstance().filesetDispatcher().listFilesets(ns);
        return ImmutableList.of(
            new FilesetSearchEntitySource(
                toSearchEntityIdentifiers(nameIdentifiers, Entity.EntityType.FILESET)));

      case MODEL:
        nameIdentifiers = GravitinoEnv.getInstance().modelDispatcher().listModels(ns);
        return ImmutableList.of(
            new ModelSearchEntitySource(
                toSearchEntityIdentifiers(nameIdentifiers, Entity.EntityType.MODEL)));

      default:
        throw new GravitinoRuntimeException(
            "Unsupported catalog type with catalog: " + catalogType);
    }
  }

  /**
   * Lists the views under the given schema namespace. Relational catalogs are not required to
   * support views, in which case an empty result is returned.
   *
   * @param namespace the schema namespace
   * @return the view identifiers, or empty if the catalog does not support views
   */
  static Optional<NameIdentifier[]> listViews(Namespace namespace) {
    ViewDispatcher viewDispatcher = GravitinoEnv.getInstance().viewDispatcher();
    if (viewDispatcher == null) {
      return Optional.empty();
    }

    try {
      return Optional.of(viewDispatcher.listViews(namespace));
    } catch (UnsupportedOperationException e) {
      LOG.debug("The catalog of schema {} does not support views", namespace);
      return Optional.empty();
    }
  }

  /**
   * Lists the functions under the given schema namespace. Relational catalogs are not required to
   * support functions, in which case an empty result is returned.
   *
   * @param namespace the schema namespace
   * @return the function identifiers, or empty if the catalog does not support functions
   */
  static Optional<NameIdentifier[]> listFunctions(Namespace namespace) {
    FunctionDispatcher functionDispatcher = GravitinoEnv.getInstance().functionDispatcher();
    if (functionDispatcher == null) {
      return Optional.empty();
    }

    try {
      return Optional.of(functionDispatcher.listFunctions(namespace));
    } catch (UnsupportedOperationException e) {
      LOG.debug("The catalog of schema {} does not support functions", namespace);
      return Optional.empty();
    }
  }

  static List<SearchEntityIdentifier> toSearchEntityIdentifiers(
      NameIdentifier[] nameIdentifiers, Entity.EntityType entityType) {
    return Arrays.stream(nameIdentifiers)
        .map(iden -> SearchEntityIdentifier.of(iden, entityType))
        .collect(Collectors.toList());
  }

  /**
   * Returns the internal access-control dispatcher when available so search synchronization does
   * not emit read events. Tests and partially initialized environments may only provide the public
   * dispatcher, which is used as a fallback.
   *
   * @return The available access-control dispatcher, or null when authorization is disabled.
   */
  static AccessControlDispatcher accessControlDispatcher() {
    AccessControlDispatcher dispatcher =
        GravitinoEnv.getInstance().internalAccessControlDispatcher();
    return dispatcher != null ? dispatcher : GravitinoEnv.getInstance().accessControlDispatcher();
  }

  /**
   * Retrieve the tags for a given metadata object.
   *
   * @param nameIdentifier the name identifier of the metadata object
   * @param type the type of the metadata object
   * @return an array of tags associated with the metadata object
   */
  static Tag[] getMetadataObjectTags(NameIdentifier nameIdentifier, Entity.EntityType type) {
    if (type == Entity.EntityType.METALAKE) {
      throw new IllegalArgumentException("Metadata object 'METALAKE' does not have tags");
    }

    String metalakeName = nameIdentifier.namespace().levels()[0];
    MetadataObject object = NameIdentifierUtil.toMetadataObject(nameIdentifier, type);

    List<TagDTO> tags = Lists.newArrayList();
    Tag[] nonInheritedTags =
        GravitinoEnv.getInstance()
            .tagDispatcher()
            .listTagsInfoForMetadataObject(metalakeName, object);
    if (ArrayUtils.isNotEmpty(nonInheritedTags)) {
      Collections.addAll(
          tags,
          Arrays.stream(nonInheritedTags)
              .map(t -> DTOConverters.toDTO(t, Optional.of(false)))
              .toArray(TagDTO[]::new));
    }

    MetadataObject parentObject = MetadataObjects.parent(object);
    while (parentObject != null) {
      Tag[] inheritedTags =
          GravitinoEnv.getInstance()
              .tagDispatcher()
              .listTagsInfoForMetadataObject(metalakeName, parentObject);
      if (ArrayUtils.isNotEmpty(inheritedTags)) {
        Collections.addAll(
            tags,
            Arrays.stream(inheritedTags)
                .map(t -> DTOConverters.toDTO(t, Optional.of(true)))
                .toArray(TagDTO[]::new));
      }
      parentObject = MetadataObjects.parent(parentObject);
    }

    return tags.toArray(new Tag[0]);
  }
}
