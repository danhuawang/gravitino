/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.metrics;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.utils.NameIdentifierUtil;

@Getter
public class AssetNode {
  // Unique ID of the asset; -1 if this asset does not exist in the metadata store
  private final long id;
  private final String name;
  private final NameIdentifier nameIdent;
  private final MetadataObject.Type type;
  // ID of the parent asset; 0 if this is the root asset, -1 if parent does not have an ID
  private final long parentId;
  // Identifier of the parent asset; null if this is the root asset
  private NameIdentifier parentIdent;
  private final Set<AssetNode> children = new HashSet<>();
  private final Set<Owner> owners;

  public AssetNode(
      long id, String name, MetadataObject.Type type, AssetNode parentNode, Set<Owner> owners) {
    this.id = id;
    this.name = name;
    this.type = type;
    this.parentId = parentNode == null ? 0 : parentNode.id;
    this.parentIdent = parentNode == null ? null : parentNode.nameIdent;
    this.nameIdent =
        parentNode == null
            ? NameIdentifierUtil.ofMetalake(name)
            : NameIdentifier.of(
                Namespace.of(
                    ArrayUtils.addAll(parentIdent.namespace().levels(), parentIdent.name())),
                name);
    this.owners = owners == null ? new HashSet<>() : owners;
  }

  public void addChild(AssetNode child) {
    children.add(child);
  }

  public void addChildren(Collection<AssetNode> childNodes) {
    children.addAll(childNodes);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AssetNode assetNode = (AssetNode) o;
    return Objects.equals(nameIdent, assetNode.nameIdent);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nameIdent);
  }
}
