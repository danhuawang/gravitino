/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;

class SearchEntityIdentifier {
  private final NameIdentifier entityIdent;
  private final Entity.EntityType entityType;

  public SearchEntityIdentifier(MetadataObject metadataObject, String metalake) {
    if (metadataObject == null) {
      this.entityType = Entity.EntityType.METALAKE;
      this.entityIdent = NameIdentifier.of(metalake);
      return;
    }

    this.entityIdent = MetadataObjectUtil.toEntityIdent(metalake, metadataObject);
    this.entityType = MetadataObjectUtil.toEntityType(metadataObject);
  }

  public static SearchEntityIdentifier of(
      NameIdentifier nameIdentifier, Entity.EntityType entityType) {
    MetadataObject metadataObject = NameIdentifierUtil.toMetadataObject(nameIdentifier, entityType);
    String metalake = NameIdentifierUtil.getMetalake(nameIdentifier);
    return new SearchEntityIdentifier(metadataObject, metalake);
  }

  public NameIdentifier entityIdent() {
    return entityIdent;
  }

  public Entity.EntityType entityType() {
    return entityType;
  }

  public String fullName() {
    String nameIdentString = entityIdent.toString();
    return entityType != Entity.EntityType.METALAKE
        ? nameIdentString.substring(nameIdentString.indexOf("." + 1))
        : "";
  }

  public String metalake() {
    return NameIdentifierUtil.getMetalake(entityIdent);
  }
}
