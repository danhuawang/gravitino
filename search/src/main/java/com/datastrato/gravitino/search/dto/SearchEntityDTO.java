/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Entity.EntityType;

@Getter
@JsonDeserialize(builder = SearchEntityDTO.SearchEntityDTOBuilder.class)
public class SearchEntityDTO {

  private final long entityId;

  private final EntityType entityType;

  private final boolean inUse;

  private final String metalake;

  private final String entityName;

  private final String entityComment;

  private final String catalogName;

  private final String fullQualifiedName;

  private final List<SearchTagDTO> tags;

  private final List<String> policyNames;

  private final SearchAuditDTO searchAudit;

  private final String owner;

  private final List<SearchUserPermissionDTO> userPermissions;

  private final List<SearchRolePermissionDTO> rolePermissions;

  private final List<PropertyDTO> entityProperties;

  private final long updateTime;

  public SearchEntityDTO(Builder<?, ?> builder) {
    this.entityId = builder.entityId;
    this.entityType = builder.entityType;
    this.inUse = builder.inUse;
    this.metalake = builder.metalake;
    this.entityName = builder.entityName;
    this.entityComment = builder.entityComment;
    this.catalogName = builder.catalogName;
    this.fullQualifiedName = builder.fullQualifiedName;
    this.tags = builder.tags;
    this.policyNames = builder.policyNames;
    this.searchAudit = builder.searchAudit;
    this.owner = builder.owner;
    this.userPermissions = builder.userPermissions;
    this.rolePermissions = builder.rolePermissions;
    this.entityProperties = builder.entityProperties;
    this.updateTime = builder.updateTime;
  }

  public abstract static class Builder<SELF extends Builder<SELF, T>, T extends SearchEntityDTO> {
    private long entityId;
    private EntityType entityType;
    private boolean inUse;
    private String metalake;
    private String entityName;
    private String entityComment;
    private String catalogName;
    private String fullQualifiedName;
    private List<SearchTagDTO> tags;
    private List<String> policyNames;
    private SearchAuditDTO searchAudit;
    private String owner;
    private List<SearchUserPermissionDTO> userPermissions;
    private List<SearchRolePermissionDTO> rolePermissions;
    private List<PropertyDTO> entityProperties;
    private long updateTime;

    public SELF withEntityId(long entityId) {
      this.entityId = entityId;
      return self();
    }

    public SELF withEntityType(EntityType entityType) {
      this.entityType = entityType;
      return self();
    }

    public SELF withInUse(boolean inUse) {
      this.inUse = inUse;
      return self();
    }

    public SELF withMetalake(String metalake) {
      this.metalake = metalake;
      return self();
    }

    public SELF withEntityName(String entityName) {
      this.entityName = entityName;
      return self();
    }

    public SELF withEntityComment(String entityComment) {
      this.entityComment = entityComment;
      return self();
    }

    public SELF withCatalogName(String catalogName) {
      this.catalogName = catalogName;
      return self();
    }

    public SELF withFullQualifiedName(String fullQualifiedName) {
      this.fullQualifiedName = fullQualifiedName;
      return self();
    }

    public SELF withTags(List<SearchTagDTO> tags) {
      this.tags = tags;
      return self();
    }

    /**
     * Sets the names of policies associated with this entity.
     *
     * @param policyNames the associated policy names
     * @return this builder
     */
    public SELF withPolicyNames(List<String> policyNames) {
      this.policyNames = policyNames;
      return self();
    }

    public SELF withSearchAudit(SearchAuditDTO searchAudit) {
      this.searchAudit = searchAudit;
      return self();
    }

    public SELF withOwner(String owner) {
      this.owner = owner;
      return self();
    }

    public SELF withUserPermissions(List<SearchUserPermissionDTO> userPermissions) {
      this.userPermissions = userPermissions;
      return self();
    }

    public SELF withRolePermissions(List<SearchRolePermissionDTO> rolePermissions) {
      this.rolePermissions = rolePermissions;
      return self();
    }

    public SELF withEntityProperties(List<PropertyDTO> entityProperties) {
      this.entityProperties = entityProperties;
      return self();
    }

    public SELF withUpdateTime(long updateTime) {
      this.updateTime = updateTime;
      return self();
    }

    public T build() {
      T t = internalBuild();
      return t;
    }

    private SELF self() {
      return (SELF) this;
    }

    protected void validate() {
      Preconditions.checkArgument(entityId > 0, "entityId must be positive");
      Preconditions.checkArgument(entityType != null, "entityType cannot be null");
      Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
      Preconditions.checkArgument(StringUtils.isNotBlank(entityName), "entityName cannot be blank");
      if (hasCatalog(entityType)) {
        Preconditions.checkArgument(
            StringUtils.isNotBlank(catalogName), "catalogName cannot be blank");
      }
    }

    private static boolean hasCatalog(EntityType type) {
      return type != EntityType.TAG
          && type != EntityType.POLICY
          && type != EntityType.USER
          && type != EntityType.GROUP
          && type != EntityType.ROLE;
    }

    protected abstract T internalBuild();
  }

  public static class SearchEntityDTOBuilder
      extends SearchEntityDTO.Builder<SearchEntityDTOBuilder, SearchEntityDTO> {

    private SearchEntityDTOBuilder() {
      super();
    }

    @Override
    protected SearchEntityDTO internalBuild() {
      validate();
      return new SearchEntityDTO(this);
    }

    public static SearchEntityDTOBuilder builder() {
      return new SearchEntityDTOBuilder();
    }
  }

  @Getter
  @JsonDeserialize(builder = SearchTagDTO.Builder.class)
  public static class SearchTagDTO {

    private final String tagName;

    private final String tagComment;

    private final Map<String, String> properties;

    private SearchTagDTO(Builder builder) {
      this.tagName = builder.tagName;
      this.tagComment = builder.tagComment;
      this.properties = builder.properties;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static class Builder {
      private String tagName;
      private String tagComment;
      private Map<String, String> properties;

      private Builder() {}

      public Builder withTagName(String tagName) {
        this.tagName = tagName;
        return this;
      }

      public Builder withTagComment(String tagComment) {
        this.tagComment = tagComment;
        return this;
      }

      public Builder withProperties(Map<String, String> properties) {
        this.properties = properties;
        return this;
      }

      public SearchTagDTO build() {
        Preconditions.checkArgument(StringUtils.isNotBlank(tagName), "tagName cannot be blank");
        return new SearchTagDTO(this);
      }
    }
  }

  @JsonDeserialize(builder = SearchAuditDTO.Builder.class)
  @Getter
  public static class SearchAuditDTO {

    private final LocalDateTime createTime;

    private final String creator;

    private final LocalDateTime lastModifiedTime;

    private final String lastModifier;

    private SearchAuditDTO(Builder builder) {
      this.createTime = builder.createTime;
      this.creator = builder.creator;
      this.lastModifiedTime = builder.lastModifiedTime;
      this.lastModifier = builder.lastModifier;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static class Builder {
      private LocalDateTime createTime;
      private String creator;
      private LocalDateTime lastModifiedTime;
      private String lastModifier;

      private Builder() {}

      public Builder withCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
        return this;
      }

      public Builder withCreator(String creator) {
        this.creator = creator;
        return this;
      }

      public Builder withLastModifiedTime(LocalDateTime lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
        return this;
      }

      public Builder withLastModifier(String lastModifier) {
        this.lastModifier = lastModifier;
        return this;
      }

      public SearchAuditDTO build() {
        return new SearchAuditDTO(this);
      }
    }
  }

  @JsonDeserialize(builder = SearchUserPermissionDTO.Builder.class)
  @Getter
  public static class SearchUserPermissionDTO {

    private final String name;

    private final String permission;

    private SearchUserPermissionDTO(Builder builder) {
      this.name = builder.name;
      this.permission = builder.permission;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static class Builder {
      private String name;
      private String permission;

      private Builder() {}

      public Builder withName(String name) {
        this.name = name;
        return this;
      }

      public Builder withPermission(String permission) {
        this.permission = permission;
        return this;
      }

      public SearchUserPermissionDTO build() {
        Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
        return new SearchUserPermissionDTO(this);
      }
    }
  }

  @JsonDeserialize(builder = SearchRolePermissionDTO.Builder.class)
  @Getter
  public static class SearchRolePermissionDTO {
    private final String name;

    private final String permission;

    private SearchRolePermissionDTO(String name, String permission) {
      this.name = name;
      this.permission = permission;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static class Builder {
      private String name;
      private String permission;

      private Builder() {}

      public Builder withName(String name) {
        this.name = name;
        return this;
      }

      public Builder withPermission(String permission) {
        this.permission = permission;
        return this;
      }

      public SearchRolePermissionDTO build() {
        Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
        return new SearchRolePermissionDTO(name, permission);
      }
    }
  }

  @AllArgsConstructor
  @Getter
  @lombok.Builder(toBuilder = true, setterPrefix = "with")
  @JsonDeserialize(builder = PropertyDTO.PropertyDTOBuilder.class)
  public static class PropertyDTO {
    static final String KEY_NAME = "key";
    static final String VALUE_NAME = "value";

    private final String key;
    private final String value;
  }
}
