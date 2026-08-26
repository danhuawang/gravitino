/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.dto.SearchEntityDTO;
import com.datastrato.gravitino.search.parser.Condition;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.store.SearchDataSource;
import com.datastrato.gravitino.search.store.SearchStorage;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-syncs the entities a tag is attached to, so that the tag data denormalized onto their
 * documents stays current when the tag is renamed, deleted, or its associations change.
 *
 * <p>This source deliberately produces no document of its own. Tag definitions are indexed by
 * {@link TagSearchEntitySource} instead.
 */
class TagAssociationSearchEntitySource extends ParentEntitySource {
  private static final Logger LOG = LoggerFactory.getLogger(TagAssociationSearchEntitySource.class);

  private final String metalake;
  private final String tagName;
  private final Supplier<List<SearchEntityIdentifier>> taggedEntities;
  private final boolean childCascading;
  private final boolean ignoreNotFound;

  /**
   * Re-syncs the entities Gravitino currently reports as carrying the tag. Use this while the tag
   * still exists, for instance after it was renamed or associated with a new object.
   *
   * @param metalake The metalake owning the tag.
   * @param tagName The tag name.
   * @return A source over the entities carrying the tag.
   */
  static TagAssociationSearchEntitySource ofAssociatedEntities(String metalake, String tagName) {
    return new TagAssociationSearchEntitySource(
        metalake, tagName, () -> listAssociatedEntities(metalake, tagName), true, false);
  }

  /**
   * Re-syncs entities carrying a deleted tag by discovering them lazily from the search index.
   *
   * @param metalake The metalake owning the tag.
   * @param tagName The deleted tag name.
   * @param storage The search storage containing the stale tag associations.
   * @return A lazy source over the indexed entities carrying the tag.
   */
  static TagAssociationSearchEntitySource ofIndexedEntities(
      String metalake, String tagName, SearchStorage storage) {
    return ofIndexedEntities(
        metalake, tagName, () -> findEntitiesByTag(metalake, tagName, storage));
  }

  static TagAssociationSearchEntitySource ofIndexedEntities(
      String metalake, String tagName, Supplier<List<SearchEntityIdentifier>> taggedEntities) {
    return new TagAssociationSearchEntitySource(metalake, tagName, taggedEntities, false, true);
  }

  private TagAssociationSearchEntitySource(
      String metalake,
      String tagName,
      Supplier<List<SearchEntityIdentifier>> taggedEntities,
      boolean childCascading,
      boolean ignoreNotFound) {
    // We use NameIdentifier.of(metalake) to create the search entity identifier for the Tag.
    super(SearchEntityIdentifier.of(NameIdentifier.of(metalake), EntityType.METALAKE), true);
    this.metalake = metalake;
    this.tagName = tagName;
    this.taggedEntities = taggedEntities;
    this.childCascading = childCascading;
    this.ignoreNotFound = ignoreNotFound;
  }

  @Override
  protected List<SearchEntitySource> createChildEntitySources() {
    List<SearchEntitySource> searchEntitySources = new ArrayList<>();
    for (SearchEntityIdentifier identifier : taggedEntities.get()) {
      try {
        searchEntitySources.add(
            SearchEntitySource.createSearchEntitySource(identifier, childCascading));
      } catch (Exception e) {
        handleChildSourceFailure(identifier, e);
      }
    }

    return searchEntitySources;
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    throw new GravitinoRuntimeException("Should never come here");
  }

  @Override
  protected boolean hasSelfSearchEntityPO() {
    return false;
  }

  protected boolean addChildEntitySources() {
    try {
      childSources = createChildEntitySources();
      currentChildIndex = 0;
      synchronized (entityCountMap) {
        int total = 0;
        for (SearchEntitySource childSource : childSources) {
          total += childSource.approximateEntityCount();
        }

        entityCountMap.put(composeKey(), total);
      }
      return !childSources.isEmpty();
    } catch (Exception e) {
      processFailedList.add(searchEntityIdentifier);
      LOG.error("Failed to create source from entity for metadata: {}", composeKey(), e);
    }
    return false;
  }

  private static List<SearchEntityIdentifier> listAssociatedEntities(
      String metalake, String tagName) {
    MetadataObject[] objects =
        GravitinoEnv.getInstance().tagDispatcher().listMetadataObjectsForTag(metalake, tagName);
    List<SearchEntityIdentifier> identifiers = new ArrayList<>();
    for (MetadataObject object : objects) {
      identifiers.add(new SearchEntityIdentifier(object, metalake));
    }
    return identifiers;
  }

  private static List<SearchEntityIdentifier> findEntitiesByTag(
      String metalake, String tagName, SearchStorage storage) {
    SearchDataSource source =
        storage.search(
            metalake,
            null,
            new Condition.InCondition("tag_name", ImmutableList.of(tagName)),
            ImmutableList.of());

    List<SearchEntityIdentifier> identifiers = new ArrayList<>();
    SearchDataSource.Result result = source.nextBatch();
    while (!result.isEmpty()) {
      EntityType entityType = result.entityType();
      for (SearchEntityDTO entity : result.entities()) {
        // The indexed full qualified name is relative to the metalake, the identifier is not.
        identifiers.add(
            SearchEntityIdentifier.of(
                NameIdentifier.parse(metalake + "." + entity.getFullQualifiedName()), entityType));
      }
      result = source.nextBatch();
    }
    return identifiers;
  }

  private void handleChildSourceFailure(SearchEntityIdentifier identifier, Exception exception) {
    if (ignoreNotFound
        && (exception instanceof NotFoundException || exception instanceof NoSuchEntityException)) {
      LOG.debug(
          "Skipping missing entity {} carrying tag {} in metalake {}",
          identifier.entityIdent(),
          tagName,
          metalake);
      return;
    }

    processFailedList.add(identifier);
    LOG.error(
        "Failed to create source for entity {} carrying tag: {} in metalake: {}",
        identifier.entityIdent(),
        tagName,
        metalake,
        exception);
  }

  private String composeKey() {
    return metalake + "@" + tagName;
  }

  @Override
  public int approximateEntityCount() {
    synchronized (entityCountMap) {
      return entityCountMap.getOrDefault(composeKey(), Integer.MAX_VALUE);
    }
  }
}
