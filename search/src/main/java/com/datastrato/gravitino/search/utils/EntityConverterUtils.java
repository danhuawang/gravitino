/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.utils;

import com.datastrato.gravitino.search.po.SearchCatalogEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchEntityPO.PropertyPO;
import com.datastrato.gravitino.search.po.SearchEntityPO.SearchTagPO;
import com.datastrato.gravitino.search.po.SearchModelEntityPO;
import com.datastrato.gravitino.search.po.SearchModelEntityPO.SearchModelVersionPO;
import com.datastrato.gravitino.search.po.SearchPolicyEntityPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO;
import com.datastrato.gravitino.search.po.SearchTableEntityPO.SearchColumn;
import com.datastrato.gravitino.search.po.SearchViewEntityPO;
import com.datastrato.gravitino.search.utils.PermissionProjectionCache.Permissions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.gravitino.Audit;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.Group;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.authorization.OwnerDispatcher;
import org.apache.gravitino.authorization.Role;
import org.apache.gravitino.authorization.User;
import org.apache.gravitino.catalog.EntityCombinedFileset;
import org.apache.gravitino.catalog.EntityCombinedModel;
import org.apache.gravitino.catalog.EntityCombinedSchema;
import org.apache.gravitino.catalog.EntityCombinedTable;
import org.apache.gravitino.catalog.EntityCombinedTopic;
import org.apache.gravitino.catalog.EntityCombinedView;
import org.apache.gravitino.connector.CatalogInfo;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.function.Function;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.meta.FilesetEntity;
import org.apache.gravitino.meta.ModelEntity;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.meta.TopicEntity;
import org.apache.gravitino.meta.ViewEntity;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntityConverterUtils {
  private static final Logger LOG = LoggerFactory.getLogger(EntityConverterUtils.class);

  private EntityConverterUtils() {
    // Prevent instantiation
  }

  /**
   * Converts a tag definition into a search entity.
   *
   * @param tag the tag definition
   * @param nameIdentifier the tag identifier
   * @return the tag search entity
   */
  public static SearchEntityPO toTagSearchEntityPO(Tag tag, NameIdentifier nameIdentifier) {
    if (!(tag instanceof HasIdentifier)) {
      throw new GravitinoRuntimeException(
          String.format("Tag %s does not expose an entity id", tag.name()));
    }

    String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(((HasIdentifier) tag).id())
        .withEntityType(EntityType.TAG)
        .withInUse(true)
        .withMetalake(metalake)
        .withEntityName(tag.name())
        .withEntityComment(tag.comment())
        .withFullQualifiedName(tag.name())
        .withTags(Collections.emptyList())
        .withPolicyNames(Collections.emptyList())
        .withSearchAudit(toSearchAudit(tag.auditInfo()))
        .withOwner(
            getMetadataObjectOwner(
                NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.TAG), metalake))
        .withEntityProperties(
            mapToKeyValueObjects(
                tag.properties() == null ? Collections.emptyMap() : tag.properties()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  /**
   * Converts a policy definition into a search entity.
   *
   * @param policy the policy definition
   * @param nameIdentifier the policy identifier
   * @return the policy search entity
   */
  public static SearchPolicyEntityPO toPolicySearchEntityPO(
      PolicyEntity policy, NameIdentifier nameIdentifier) {
    String content;
    try {
      content = JsonUtils.anyFieldMapper().writeValueAsString(policy.content());
    } catch (JsonProcessingException e) {
      throw new GravitinoRuntimeException("Failed to serialize policy content: %s", e.getMessage());
    }

    String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
    return SearchPolicyEntityPO.Builder.builder()
        .withEntityId(policy.id())
        .withEntityType(EntityType.POLICY)
        .withInUse(true)
        .withMetalake(metalake)
        .withEntityName(policy.name())
        .withEntityComment(policy.comment())
        .withFullQualifiedName(policy.name())
        .withTags(Collections.emptyList())
        .withPolicyNames(Collections.emptyList())
        .withSearchAudit(toSearchAudit(policy.auditInfo()))
        .withOwner(
            getMetadataObjectOwner(
                NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.POLICY), metalake))
        .withEntityProperties(Collections.emptyList())
        .withUpdateTime(System.currentTimeMillis())
        .withPolicyType(policy.policyType().policyType())
        .withEnabled(policy.enabled())
        .withContent(content)
        .build();
  }

  private static List<String> getMetadataObjectPolicyNames(
      NameIdentifier nameIdentifier, EntityType entityType) {
    PolicyDispatcher policyDispatcher = GravitinoEnv.getInstance().internalPolicyDispatcher();
    if (policyDispatcher == null) {
      return ImmutableList.of();
    }

    String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
    MetadataObject metadataObject = NameIdentifierUtil.toMetadataObject(nameIdentifier, entityType);

    Set<String> policyNames = new LinkedHashSet<>();
    addPolicyNames(policyDispatcher, metalake, metadataObject, policyNames);
    for (MetadataObject parent : MetadataObjectUtil.getParentMetadataObjects(metadataObject)) {
      addPolicyNames(policyDispatcher, metalake, parent, policyNames);
    }
    return ImmutableList.copyOf(policyNames);
  }

  private static void addPolicyNames(
      PolicyDispatcher policyDispatcher,
      String metalake,
      MetadataObject metadataObject,
      Set<String> policyNames) {
    PolicyEntity[] policies =
        policyDispatcher.listPolicyInfosForMetadataObject(metalake, metadataObject);
    if (ArrayUtils.isNotEmpty(policies)) {
      Arrays.stream(policies).map(PolicyEntity::name).forEach(policyNames::add);
    }
  }

  @Nullable
  private static String getMetadataObjectOwner(MetadataObject metadataObject, String metalake) {
    OwnerDispatcher ownerDispatcher = GravitinoEnv.getInstance().internalOwnerDispatcher();
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
      CatalogInfo catalog, Tag[] tags, NameIdentifier nameIdentifier) {
    String inUseString = catalog.properties().get("in-use");
    boolean inUse = inUseString == null || Boolean.parseBoolean(inUseString);
    String[] levels = nameIdentifier.namespace().levels();

    String owner =
        getMetadataObjectOwner(
            NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.CATALOG), levels[0]);
    Permissions permissions = getPermissions(nameIdentifier, EntityType.CATALOG);
    // Catalog properties may contain credentials, so this conversion intentionally omits them.
    return SearchCatalogEntityPO.SearchCatalogEntityPOBuilder.builder()
        .withEntityId(catalog.id())
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
        .withPolicyNames(getMetadataObjectPolicyNames(nameIdentifier, EntityType.CATALOG))
        .withSearchAudit(toSearchAudit(catalog.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(permissions.userPermissions())
        .withRolePermissions(permissions.rolePermissions())
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
    Permissions permissions = getPermissions(nameIdentifier, EntityType.SCHEMA);
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
        .withPolicyNames(getMetadataObjectPolicyNames(nameIdentifier, EntityType.SCHEMA))
        .withSearchAudit(toSearchAudit(schema.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(permissions.userPermissions())
        .withRolePermissions(permissions.rolePermissions())
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
    Permissions permissions = getPermissions(nameIdentifier, EntityType.TOPIC);
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
        .withPolicyNames(getMetadataObjectPolicyNames(nameIdentifier, EntityType.TOPIC))
        .withSearchAudit(toSearchAudit(topic.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(permissions.userPermissions())
        .withRolePermissions(permissions.rolePermissions())
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
    Permissions permissions = getPermissions(nameIdentifier, EntityType.MODEL);

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
        .withPolicyNames(getMetadataObjectPolicyNames(nameIdentifier, EntityType.MODEL))
        .withSearchAudit(toSearchAudit(model.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(permissions.userPermissions())
        .withRolePermissions(permissions.rolePermissions())
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
    Permissions permissions = getPermissions(nameIdentifier, EntityType.FILESET);

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
        .withPolicyNames(getMetadataObjectPolicyNames(nameIdentifier, EntityType.FILESET))
        .withSearchAudit(toSearchAudit(fileset.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(permissions.userPermissions())
        .withRolePermissions(permissions.rolePermissions())
        .withEntityProperties(mapToKeyValueObjects(fileset.properties()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  public static SearchTableEntityPO toTableSearchEntityPO(
      EntityCombinedTable table, Tag[] tags, NameIdentifier nameIdentifier) {
    String inUseString = table.properties().get("in-use");
    boolean inUse = inUseString == null || Boolean.parseBoolean(inUseString);
    TableEntity tableEntity = table.tableFromGravitino();

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
    Permissions permissions = getPermissions(nameIdentifier, EntityType.TABLE);

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
        .withPolicyNames(getMetadataObjectPolicyNames(nameIdentifier, EntityType.TABLE))
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
        .withUserPermissions(permissions.userPermissions())
        .withRolePermissions(permissions.rolePermissions())
        .withEntityProperties(mapToKeyValueObjects(table.properties()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  /**
   * Converts a view loaded from Gravitino to its search persistent object.
   *
   * @param view The combined view metadata.
   * @param tags The tags attached to the view, including the inherited ones.
   * @param nameIdentifier The name identifier of the view.
   * @return The persistent object to index.
   */
  public static SearchViewEntityPO toViewSearchEntityPO(
      EntityCombinedView view, Tag[] tags, NameIdentifier nameIdentifier) {
    Map<String, String> properties = view.properties();
    String inUseString = properties.get("in-use");
    boolean inUse = inUseString == null || Boolean.parseBoolean(inUseString);
    ViewEntity viewEntity = view.viewEntity();

    long id =
        viewEntity != null
            ? viewEntity.id()
            : getEntityIdFromProperties(view.viewFromCatalog().properties(), EntityType.VIEW);

    String metalakeName = nameIdentifier.namespace().levels()[0];
    String catalog = nameIdentifier.namespace().levels()[1];
    String schema = nameIdentifier.namespace().levels()[2];
    String owner =
        getMetadataObjectOwner(
            NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.VIEW), metalakeName);

    return SearchViewEntityPO.SearchViewEntityPOBuilder.builder()
        .withEntityId(id)
        .withEntityType(EntityType.VIEW)
        .withInUse(inUse)
        .withMetalake(metalakeName)
        .withEntityName(view.name())
        .withEntityComment(view.comment())
        .withCatalogName(catalog)
        .withFullQualifiedName(String.format("%s.%s.%s", catalog, schema, view.name()))
        .withTags(toSearchTag(tags))
        .withSearchAudit(toSearchAudit(view.auditInfo()))
        .withColumns(toSearchColumns(view.columns()))
        .withOwner(owner)
        .withUserPermissions(null)
        .withRolePermissions(null)
        .withEntityProperties(mapToKeyValueObjects(properties))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  /**
   * Converts a User to the lightweight search projection defined by the v2 User index template.
   *
   * @param user The User metadata.
   * @param metalake The metalake containing the User.
   * @return The persistent object to index.
   */
  public static SearchEntityPO toUserSearchEntityPO(User user, String metalake) {
    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(user.id())
        .withEntityType(EntityType.USER)
        .withMetalake(metalake)
        .withEntityName(user.name())
        .withSearchAudit(toSearchAudit(user.auditInfo()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  /**
   * Converts a Group to the lightweight search projection defined by the v2 Group index template.
   *
   * @param group The Group metadata.
   * @param metalake The metalake containing the Group.
   * @return The persistent object to index.
   */
  public static SearchEntityPO toGroupSearchEntityPO(Group group, String metalake) {
    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(group.id())
        .withEntityType(EntityType.GROUP)
        .withMetalake(metalake)
        .withEntityName(group.name())
        .withSearchAudit(toSearchAudit(group.auditInfo()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  /**
   * Converts a function loaded from Gravitino to the lightweight search projection defined by the
   * v2 Function index template.
   *
   * @param function The function metadata.
   * @param tags The tags attached to the function, including inherited tags.
   * @param nameIdentifier The function name identifier.
   * @return The persistent object to index.
   */
  public static SearchEntityPO toFunctionSearchEntityPO(
      Function function, Tag[] tags, NameIdentifier nameIdentifier) {
    if (!(function instanceof HasIdentifier)) {
      throw new GravitinoRuntimeException(
          "Cannot resolve the entity id of function %s, unexpected function implementation %s",
          nameIdentifier, function.getClass().getName());
    }

    String metalakeName = nameIdentifier.namespace().levels()[0];
    String catalog = nameIdentifier.namespace().levels()[1];
    String schema = nameIdentifier.namespace().levels()[2];
    String owner =
        getMetadataObjectOwner(
            NameIdentifierUtil.toMetadataObject(nameIdentifier, EntityType.FUNCTION), metalakeName);

    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(((HasIdentifier) function).id())
        .withEntityType(EntityType.FUNCTION)
        .withInUse(true)
        .withMetalake(metalakeName)
        .withEntityName(function.name())
        .withEntityComment(function.comment())
        .withCatalogName(catalog)
        .withFullQualifiedName(String.format("%s.%s.%s", catalog, schema, function.name()))
        .withTags(toSearchTag(tags))
        .withSearchAudit(toSearchAudit(function.auditInfo()))
        .withOwner(owner)
        .withUserPermissions(null)
        .withRolePermissions(null)
        .withEntityProperties(Collections.emptyList())
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  /**
   * Converts a Role to the lightweight search projection defined by the v2 Role index template.
   *
   * @param role The Role metadata.
   * @param metalake The metalake containing the Role.
   * @return The persistent object to index.
   */
  public static SearchEntityPO toRoleSearchEntityPO(Role role, String metalake) {
    if (!(role instanceof HasIdentifier)) {
      throw new IllegalArgumentException("Role does not expose a Gravitino entity identifier");
    }

    return SearchEntityPO.SearchEntityPOBuilder.builder()
        .withEntityId(((HasIdentifier) role).id())
        .withEntityType(EntityType.ROLE)
        .withMetalake(metalake)
        .withEntityName(role.name())
        .withSearchAudit(toSearchAudit(role.auditInfo()))
        .withUpdateTime(System.currentTimeMillis())
        .build();
  }

  private static Permissions getPermissions(NameIdentifier nameIdentifier, EntityType entityType) {
    String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
    MetadataObject object = NameIdentifierUtil.toMetadataObject(nameIdentifier, entityType);
    return PermissionProjectionCache.getPermissions(metalake, object);
  }

  private static List<SearchColumn> toSearchColumns(Column[] columns) {
    if (ArrayUtils.isEmpty(columns)) {
      return Collections.emptyList();
    }

    return Arrays.stream(columns)
        .map(
            column ->
                SearchColumn.builder()
                    .withColumnName(column.name())
                    .withColumnComment(column.comment())
                    .build())
        .collect(Collectors.toList());
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
