/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.tag;

import com.datastrato.gravitino.tag.mapper.DatastratoTagPolicyMetadataObjectMapper;
import com.datastrato.gravitino.tag.po.DatastratoPolicyRelPO;
import com.datastrato.gravitino.tag.po.DatastratoTagRelPO;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.gravitino.Entity;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.meta.CatalogEntity;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.FunctionEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.storage.relational.po.PolicyPO;
import org.apache.gravitino.storage.relational.po.PolicyVersionPO;
import org.apache.gravitino.storage.relational.po.TagPO;
import org.apache.gravitino.storage.relational.utils.POConverters;
import org.apache.gravitino.storage.relational.utils.SessionUtils;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NamespaceUtil;

/** Helper class for batch fetching tags and policies for metadata objects. */
public class DatastratoTagPolicyBatchHelper {

  private DatastratoTagPolicyBatchHelper() {}

  /**
   * Batch fetches direct tags and policies for a list of metadata objects.
   *
   * <p>Entity IDs are resolved once per metadata object type and namespace, then shared by the tag
   * and policy relation queries. Objects that exist in a federated catalog but have not been stored
   * in Gravitino yet remain in the result with empty tag and policy arrays.
   *
   * @param metalake The metalake name
   * @param objects The metadata objects
   * @return The direct tag and policy entities
   */
  public static TagPolicyBatchResult batchFetchDirectTagPolicies(
      String metalake, List<MetadataObject> objects) {
    if (objects == null || objects.isEmpty()) {
      return new TagPolicyBatchResult(new LinkedHashMap<>(), new LinkedHashMap<>());
    }
    return batchFetchDirectTagPolicies(
        metalake, objects, Collections.emptyMap(), GravitinoEnv.getInstance().entityStore());
  }

  /**
   * Batch fetches direct tags and policies while reusing entity IDs resolved by the caller.
   *
   * <p>An empty optional means the caller has already determined that the object is not stored, so
   * this helper will not query the entity store for that object again. Objects absent from the map
   * are resolved in batches by type and namespace.
   *
   * @param metalake The metalake name
   * @param objects The metadata objects
   * @param knownEntityIds Entity IDs already resolved by the caller
   * @return The direct tag and policy entities
   */
  public static TagPolicyBatchResult batchFetchDirectTagPolicies(
      String metalake,
      List<MetadataObject> objects,
      Map<MetadataObject, Optional<Long>> knownEntityIds) {
    if (objects == null || objects.isEmpty()) {
      return new TagPolicyBatchResult(new LinkedHashMap<>(), new LinkedHashMap<>());
    }
    return batchFetchDirectTagPolicies(
        metalake, objects, knownEntityIds, GravitinoEnv.getInstance().entityStore());
  }

  static TagPolicyBatchResult batchFetchDirectTagPolicies(
      String metalake,
      List<MetadataObject> objects,
      Map<MetadataObject, Optional<Long>> knownEntityIds,
      EntityStore entityStore) {
    if (objects == null || objects.isEmpty()) {
      return new TagPolicyBatchResult(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    Map<MetadataObject, TagEntity[]> tags = new LinkedHashMap<>();
    Map<MetadataObject, PolicyEntity[]> policies = new LinkedHashMap<>();
    for (MetadataObject obj : objects) {
      tags.put(obj, new TagEntity[0]);
      policies.put(obj, new PolicyEntity[0]);
    }

    Map<MetadataObject.Type, Map<Long, MetadataObject>> resolvedObjects =
        resolveStoredObjects(metalake, objects, knownEntityIds, entityStore);
    if (resolvedObjects.isEmpty()) {
      return new TagPolicyBatchResult(tags, policies);
    }

    Set<Long> resolvedIds = new LinkedHashSet<>();
    resolvedObjects.values().forEach(objectsById -> resolvedIds.addAll(objectsById.keySet()));
    List<Long> metadataObjectIds = new ArrayList<>(resolvedIds);
    Namespace tagNamespace = NamespaceUtil.ofTag(metalake);
    Namespace policyNamespace = NamespaceUtil.ofPolicy(metalake);
    List<DatastratoTagRelPO> tagRelPOs =
        SessionUtils.getWithoutCommit(
            DatastratoTagPolicyMetadataObjectMapper.class,
            mapper -> mapper.batchListTagRelPOsByMetadataObjectIds(metadataObjectIds));
    Map<MetadataObject, List<TagEntity>> objectTags = new HashMap<>();
    if (tagRelPOs != null) {
      for (DatastratoTagRelPO po : tagRelPOs) {
        MetadataObject obj =
            getResolvedObject(
                resolvedObjects, po.getMetadataObjectType(), po.getMetadataObjectId());
        if (obj != null) {
          TagPO tagPO = toTagPO(po);
          TagEntity entity = POConverters.fromTagPO(tagPO, tagNamespace);
          objectTags.computeIfAbsent(obj, k -> new ArrayList<>()).add(entity);
        }
      }
    }
    for (Map.Entry<MetadataObject, List<TagEntity>> objectTagsEntry : objectTags.entrySet()) {
      tags.put(objectTagsEntry.getKey(), objectTagsEntry.getValue().toArray(new TagEntity[0]));
    }

    List<DatastratoPolicyRelPO> policyRelPOs =
        SessionUtils.getWithoutCommit(
            DatastratoTagPolicyMetadataObjectMapper.class,
            mapper -> mapper.batchListPolicyRelPOsByMetadataObjectIds(metadataObjectIds));
    Map<MetadataObject, List<PolicyEntity>> objectPolicies = new HashMap<>();
    if (policyRelPOs != null) {
      for (DatastratoPolicyRelPO po : policyRelPOs) {
        MetadataObject obj =
            getResolvedObject(
                resolvedObjects, po.getMetadataObjectType(), po.getMetadataObjectId());
        if (obj != null) {
          PolicyPO policyPO = toPolicyPO(po);
          PolicyEntity entity = POConverters.fromPolicyPO(policyPO, policyNamespace);
          objectPolicies.computeIfAbsent(obj, k -> new ArrayList<>()).add(entity);
        }
      }
    }
    for (Map.Entry<MetadataObject, List<PolicyEntity>> objectPoliciesEntry :
        objectPolicies.entrySet()) {
      policies.put(
          objectPoliciesEntry.getKey(),
          objectPoliciesEntry.getValue().toArray(new PolicyEntity[0]));
    }

    return new TagPolicyBatchResult(tags, policies);
  }

  /** Holds tags and policies fetched for a batch of metadata objects. */
  public static final class TagPolicyBatchResult {
    private final Map<MetadataObject, TagEntity[]> tags;
    private final Map<MetadataObject, PolicyEntity[]> policies;

    /**
     * Creates a batch result.
     *
     * @param tags Tags keyed by metadata object
     * @param policies Policies keyed by metadata object
     */
    public TagPolicyBatchResult(
        Map<MetadataObject, TagEntity[]> tags, Map<MetadataObject, PolicyEntity[]> policies) {
      this.tags = tags;
      this.policies = policies;
    }

    /**
     * Returns tags keyed by metadata object.
     *
     * @return The tags
     */
    public Map<MetadataObject, TagEntity[]> tags() {
      return tags;
    }

    /**
     * Returns policies keyed by metadata object.
     *
     * @return The policies
     */
    public Map<MetadataObject, PolicyEntity[]> policies() {
      return policies;
    }
  }

  private static Map<MetadataObject.Type, Map<Long, MetadataObject>> resolveStoredObjects(
      String metalake,
      List<MetadataObject> objects,
      Map<MetadataObject, Optional<Long>> knownEntityIds,
      EntityStore entityStore) {
    Map<MetadataObject.Type, Map<Namespace, Map<NameIdentifier, MetadataObject>>> groupedObjects =
        new LinkedHashMap<>();
    Map<MetadataObject.Type, Map<Long, MetadataObject>> resolvedObjects = new LinkedHashMap<>();
    for (MetadataObject object : objects) {
      if (knownEntityIds.containsKey(object)) {
        knownEntityIds
            .get(object)
            .ifPresent(
                id ->
                    resolvedObjects
                        .computeIfAbsent(object.type(), ignored -> new LinkedHashMap<>())
                        .put(id, object));
        continue;
      }

      NameIdentifier identifier = MetadataObjectUtil.toEntityIdent(metalake, object);
      groupedObjects
          .computeIfAbsent(object.type(), ignored -> new LinkedHashMap<>())
          .computeIfAbsent(identifier.namespace(), ignored -> new LinkedHashMap<>())
          .put(identifier, object);
    }

    for (Map.Entry<MetadataObject.Type, Map<Namespace, Map<NameIdentifier, MetadataObject>>>
        typeEntry : groupedObjects.entrySet()) {
      MetadataObject.Type objectType = typeEntry.getKey();
      Entity.EntityType entityType = MetadataObjectUtil.toEntityType(objectType);
      Map<Long, MetadataObject> objectsById =
          resolvedObjects.computeIfAbsent(objectType, ignored -> new LinkedHashMap<>());
      for (Map.Entry<Namespace, Map<NameIdentifier, MetadataObject>> namespaceEntry :
          typeEntry.getValue().entrySet()) {
        List<NameIdentifier> identifiers = new ArrayList<>(namespaceEntry.getValue().keySet());
        List<? extends HasIdentifier> storedEntities =
            loadStoredEntities(
                entityStore, namespaceEntry.getKey(), identifiers, objectType, entityType);
        if (storedEntities == null) {
          continue;
        }

        for (HasIdentifier storedEntity : storedEntities) {
          MetadataObject object = namespaceEntry.getValue().get(storedEntity.nameIdentifier());
          if (object != null) {
            objectsById.put(storedEntity.id(), object);
          }
        }
      }
      if (objectsById.isEmpty()) {
        resolvedObjects.remove(objectType);
      }
    }
    return resolvedObjects;
  }

  private static MetadataObject getResolvedObject(
      Map<MetadataObject.Type, Map<Long, MetadataObject>> resolvedObjects,
      String metadataObjectType,
      long metadataObjectId) {
    Map<Long, MetadataObject> objectsById =
        resolvedObjects.get(MetadataObject.Type.valueOf(metadataObjectType));
    return objectsById == null ? null : objectsById.get(metadataObjectId);
  }

  private static List<? extends HasIdentifier> loadStoredEntities(
      EntityStore entityStore,
      Namespace namespace,
      List<NameIdentifier> identifiers,
      MetadataObject.Type objectType,
      Entity.EntityType entityType) {
    try {
      switch (objectType) {
        case CATALOG:
          return entityStore.batchGet(identifiers, entityType, CatalogEntity.class);
        case SCHEMA:
          return entityStore.batchGet(identifiers, entityType, SchemaEntity.class);
        case TABLE:
          return entityStore.batchGet(identifiers, entityType, TableEntity.class);
        case FILESET:
          return entityStore.batchGet(identifiers, entityType, FilesetEntity.class);
        case TOPIC:
          return entityStore.batchGet(identifiers, entityType, TopicEntity.class);
        case MODEL:
          return entityStore.batchGet(identifiers, entityType, ModelEntity.class);
        case VIEW:
          // The relational backend does not have a true batch-get for views. A single namespace
          // listing still keeps the query count constant as the requested object count grows.
          return entityStore.list(namespace, ViewEntity.class, entityType, false);
        case FUNCTION:
          // Functions have no batch-get path either, so resolve them with one namespace listing.
          return entityStore.list(namespace, FunctionEntity.class, entityType, false);
        default:
          throw new IllegalArgumentException(
              "Unsupported metadata object type for batch tag/policy fetch: " + objectType);
      }
    } catch (NoSuchEntityException e) {
      return Collections.emptyList();
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to resolve metadata objects under namespace " + namespace, e);
    }
  }

  private static TagPO toTagPO(DatastratoTagRelPO relPO) {
    return TagPO.builder()
        .withTagId(relPO.getTagId())
        .withTagName(relPO.getTagName())
        .withMetalakeId(relPO.getMetalakeId())
        .withComment(relPO.getComment())
        .withProperties(relPO.getProperties())
        .withAuditInfo(relPO.getAuditInfo())
        .withCurrentVersion(relPO.getCurrentVersion())
        .withLastVersion(relPO.getLastVersion())
        .withDeletedAt(relPO.getDeletedAt())
        .build();
  }

  private static PolicyPO toPolicyPO(DatastratoPolicyRelPO relPO) {
    PolicyVersionPO versionPO =
        PolicyVersionPO.builder()
            .withId(relPO.getVersionId())
            .withMetalakeId(
                relPO.getVersionMetalakeId() != null
                    ? relPO.getVersionMetalakeId()
                    : relPO.getMetalakeId())
            .withPolicyId(
                relPO.getVersionPolicyId() != null
                    ? relPO.getVersionPolicyId()
                    : relPO.getPolicyId())
            .withVersion(relPO.getVersion())
            .withPolicyComment(relPO.getPolicyComment())
            .withEnabled(Boolean.TRUE.equals(relPO.getEnabled()))
            .withContent(relPO.getContent())
            .withDeletedAt(relPO.getVersionDeletedAt() != null ? relPO.getVersionDeletedAt() : 0L)
            .build();

    return PolicyPO.builder()
        .withPolicyId(relPO.getPolicyId())
        .withPolicyName(relPO.getPolicyName())
        .withPolicyType(relPO.getPolicyType())
        .withMetalakeId(relPO.getMetalakeId())
        .withAuditInfo(relPO.getAuditInfo())
        .withCurrentVersion(relPO.getCurrentVersion())
        .withLastVersion(relPO.getLastVersion())
        .withDeletedAt(relPO.getDeletedAt())
        .withPolicyVersionPO(versionPO)
        .build();
  }
}
