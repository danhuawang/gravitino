/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.search.po;

import com.fasterxml.jackson.annotation.JsonProperty;
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
@JsonDeserialize(builder = SearchEntityPO.SearchEntityPOBuilder.class)
public class SearchEntityPO {

  @JsonProperty("entity_id")
  private final long entityId;

  @JsonProperty("entity_type")
  private final EntityType entityType;

  @JsonProperty("in_use")
  private final boolean inUse;

  @JsonProperty("metalake")
  private final String metalake;

  @JsonProperty("entity_name")
  private final String entityName;

  @JsonProperty("entity_comment")
  private final String entityComment;

  @JsonProperty("catalog_name")
  private final String catalogName;

  @JsonProperty("full_qualified_name")
  private final String fullQualifiedName;

  @JsonProperty("tags")
  private final List<SearchTagPO> tags;

  @JsonProperty("search_audit")
  private final SearchAuditPO searchAudit;

  @JsonProperty("owner")
  private final String owner;

  @JsonProperty("user_permissions")
  private final List<SearchUserPermissionPO> userPermissions;

  @JsonProperty("role_permissions")
  private final List<SearchRolePermissionPO> rolePermissions;

  @JsonProperty("entity_properties")
  private final List<PropertyPO> entityProperties;

  public SearchEntityPO(Builder<?, ?> builder) {
    this.entityId = builder.entityId;
    this.entityType = builder.entityType;
    this.inUse = builder.inUse;
    this.metalake = builder.metalake;
    this.entityName = builder.entityName;
    this.entityComment = builder.entityComment;
    this.catalogName = builder.catalogName;
    this.fullQualifiedName = builder.fullQualifiedName;
    this.tags = builder.tags;
    this.searchAudit = builder.searchAudit;
    this.owner = builder.owner;
    this.userPermissions = builder.userPermissions;
    this.rolePermissions = builder.rolePermissions;
    this.entityProperties = builder.entityProperties;
  }

  public abstract static class Builder<SELF extends Builder<SELF, T>, T> {
    protected long entityId;
    protected EntityType entityType;
    protected boolean inUse;
    protected String metalake;
    protected String entityName;
    protected String entityComment;
    protected String catalogName;
    protected String fullQualifiedName;
    protected List<SearchTagPO> tags;
    protected SearchAuditPO searchAudit;
    protected String owner;
    protected List<SearchUserPermissionPO> userPermissions;
    protected List<SearchRolePermissionPO> rolePermissions;
    protected List<PropertyPO> entityProperties;

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

    public SELF withTags(List<SearchTagPO> tags) {
      this.tags = tags;
      return self();
    }

    public SELF withSearchAudit(SearchAuditPO searchAudit) {
      this.searchAudit = searchAudit;
      return self();
    }

    public SELF withOwner(String owner) {
      this.owner = owner;
      return self();
    }

    public SELF withUserPermissions(List<SearchUserPermissionPO> userPermissions) {
      this.userPermissions = userPermissions;
      return self();
    }

    public SELF withRolePermissions(List<SearchRolePermissionPO> rolePermissions) {
      this.rolePermissions = rolePermissions;
      return self();
    }

    public SELF withEntityProperties(List<PropertyPO> entityProperties) {
      this.entityProperties = entityProperties;
      return self();
    }

    protected void validate() {
      Preconditions.checkArgument(entityId > 0, "entityId must be positive");
      Preconditions.checkArgument(entityType != null, "entityType cannot be null");
      Preconditions.checkArgument(StringUtils.isNotBlank(metalake), "metalake cannot be blank");
      Preconditions.checkArgument(StringUtils.isNotBlank(entityName), "entityName cannot be blank");
      Preconditions.checkArgument(
          StringUtils.isNotBlank(catalogName), "catalogName cannot be blank");
    }

    public T build() {
      T t = internalBuild();
      return t;
    }

    private SELF self() {
      return (SELF) this;
    }

    abstract T internalBuild();
  }

  public static class SearchEntityPOBuilder extends Builder<SearchEntityPOBuilder, SearchEntityPO> {

    public static SearchEntityPOBuilder builder() {
      return new SearchEntityPOBuilder();
    }

    @Override
    public SearchEntityPO internalBuild() {
      validate();
      return new SearchEntityPO(this);
    }
  }

  @Getter
  @JsonDeserialize(builder = SearchTagPO.Builder.class)
  public static class SearchTagPO {
    @JsonProperty("tag_name")
    private final String tagName;

    @JsonProperty("tag_comment")
    private final String tagComment;

    @JsonProperty("properties")
    private final Map<String, String> properties;

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

      public SearchTagPO build() {
        Preconditions.checkArgument(StringUtils.isNotBlank(tagName), "tagName cannot be blank");
        return new SearchTagPO(this);
      }
    }

    private SearchTagPO(Builder builder) {
      this.tagName = builder.tagName;
      this.tagComment = builder.tagComment;
      this.properties = builder.properties;
    }

    public static Builder builder() {
      return new Builder();
    }
  }

  @JsonDeserialize(builder = SearchAuditPO.Builder.class)
  public static class SearchAuditPO {
    @JsonProperty("create_time")
    private final LocalDateTime createTime;

    @JsonProperty("creator")
    private final String creator;

    @JsonProperty("last_modified_time")
    private final LocalDateTime lastModifiedTime;

    @JsonProperty("last_modifier")
    private final String lastModifier;

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

      public SearchAuditPO build() {
        return new SearchAuditPO(this);
      }
    }

    private SearchAuditPO(Builder builder) {
      this.createTime = builder.createTime;
      this.creator = builder.creator;
      this.lastModifiedTime = builder.lastModifiedTime;
      this.lastModifier = builder.lastModifier;
    }

    public static Builder builder() {
      return new Builder();
    }
  }

  @JsonDeserialize(builder = SearchUserPermissionPO.Builder.class)
  public static class SearchUserPermissionPO {
    @JsonProperty("name")
    private final String name;

    @JsonProperty("permission")
    private final String permission;

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

      public SearchUserPermissionPO build() {
        return new SearchUserPermissionPO(this);
      }
    }

    private SearchUserPermissionPO(Builder builder) {
      this.name = builder.name;
      this.permission = builder.permission;
    }

    public static Builder builder() {
      return new Builder();
    }
  }

  @JsonDeserialize(builder = SearchRolePermissionPO.Builder.class)
  public static class SearchRolePermissionPO {
    @JsonProperty("name")
    private final String name;

    @JsonProperty("permission")
    private final String permission;

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

      public SearchRolePermissionPO build() {
        return new SearchRolePermissionPO(this);
      }
    }

    private SearchRolePermissionPO(Builder builder) {
      this.name = builder.name;
      this.permission = builder.permission;
    }

    public static Builder builder() {
      return new Builder();
    }
  }

  @AllArgsConstructor
  @Getter
  @lombok.Builder(toBuilder = true, setterPrefix = "with")
  @JsonDeserialize(builder = PropertyPO.PropertyPOBuilder.class)
  public static class PropertyPO {
    static final String KEY_NAME = "key";
    static final String VALUE_NAME = "value";

    private final String key;
    private final String value;
  }
}
