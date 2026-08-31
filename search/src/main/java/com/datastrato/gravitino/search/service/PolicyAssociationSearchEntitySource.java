/*
 * Copyright 2024 Datastrato Inc.
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

/** A source that reloads metadata objects associated with one policy. */
class PolicyAssociationSearchEntitySource extends ParentEntitySource {
  private static final Logger LOG =
      LoggerFactory.getLogger(PolicyAssociationSearchEntitySource.class);

  private final String metalake;
  private final String policyName;
  private final Supplier<List<SearchEntityIdentifier>> policyEntities;
  private final boolean childCascading;
  private final boolean ignoreNotFound;

  static PolicyAssociationSearchEntitySource ofAssociatedEntities(
      String metalake, String policyName) {
    return new PolicyAssociationSearchEntitySource(
        metalake, policyName, () -> listAssociatedEntities(metalake, policyName), true, false);
  }

  static PolicyAssociationSearchEntitySource ofIndexedEntities(
      String metalake, String policyName, SearchStorage storage) {
    return ofIndexedEntities(
        metalake, policyName, () -> findEntitiesByPolicy(metalake, policyName, storage));
  }

  static PolicyAssociationSearchEntitySource ofIndexedEntities(
      String metalake, String policyName, Supplier<List<SearchEntityIdentifier>> policyEntities) {
    return new PolicyAssociationSearchEntitySource(
        metalake, policyName, policyEntities, false, true);
  }

  private PolicyAssociationSearchEntitySource(
      String metalake,
      String policyName,
      Supplier<List<SearchEntityIdentifier>> policyEntities,
      boolean childCascading,
      boolean ignoreNotFound) {
    super(SearchEntityIdentifier.of(NameIdentifier.of(metalake), EntityType.METALAKE), true);
    this.metalake = metalake;
    this.policyName = policyName;
    this.policyEntities = policyEntities;
    this.childCascading = childCascading;
    this.ignoreNotFound = ignoreNotFound;
  }

  @Override
  protected List<SearchEntitySource> createChildEntitySources() {
    List<SearchEntitySource> sources = new ArrayList<>();
    for (SearchEntityIdentifier identifier : policyEntities.get()) {
      try {
        sources.add(SearchEntitySource.createSearchEntitySource(identifier, childCascading));
      } catch (Exception e) {
        handleChildSourceFailure(identifier, e);
      }
    }
    return sources;
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    throw new GravitinoRuntimeException("Policy association sources do not contain a self entity");
  }

  @Override
  protected boolean hasSelfSearchEntityPO() {
    return false;
  }

  @Override
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
      LOG.error("Failed to create source from entity for policy: {}", composeKey(), e);
      return false;
    }
  }

  @Override
  public int approximateEntityCount() {
    synchronized (entityCountMap) {
      return entityCountMap.getOrDefault(composeKey(), Integer.MAX_VALUE);
    }
  }

  private static List<SearchEntityIdentifier> listAssociatedEntities(
      String metalake, String policyName) {
    MetadataObject[] objects =
        GravitinoEnv.getInstance()
            .policyDispatcher()
            .listMetadataObjectsForPolicy(metalake, policyName);
    List<SearchEntityIdentifier> identifiers = new ArrayList<>();
    for (MetadataObject object : objects) {
      identifiers.add(new SearchEntityIdentifier(object, metalake));
    }
    return identifiers;
  }

  private static List<SearchEntityIdentifier> findEntitiesByPolicy(
      String metalake, String policyName, SearchStorage storage) {
    SearchDataSource source =
        storage.search(
            metalake,
            null,
            new Condition.InCondition("policy_name", ImmutableList.of(policyName)),
            ImmutableList.of());

    List<SearchEntityIdentifier> identifiers = new ArrayList<>();
    SearchDataSource.Result result = source.nextBatch();
    while (!result.isEmpty()) {
      EntityType entityType = result.entityType();
      for (SearchEntityDTO entity : result.entities()) {
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
          "Skipping missing entity {} carrying policy {} in metalake {}",
          identifier.entityIdent(),
          policyName,
          metalake);
      return;
    }

    processFailedList.add(identifier);
    LOG.error(
        "Failed to create source for entity {} carrying policy {} in metalake {}",
        identifier.entityIdent(),
        policyName,
        metalake,
        exception);
  }

  private String composeKey() {
    return metalake + "@" + policyName;
  }
}
