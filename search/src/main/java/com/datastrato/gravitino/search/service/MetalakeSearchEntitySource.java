/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.utils.NameIdentifierUtil;

class MetalakeSearchEntitySource extends ParentEntitySource {

  protected MetalakeSearchEntitySource(
      SearchEntityIdentifier searchEntityIdentifier, boolean cascading) {
    super(searchEntityIdentifier, cascading);
  }

  @Override
  protected boolean hasSelfSearchEntityPO() {
    return false;
  }

  @Override
  protected List<SearchEntitySource> createChildEntitySources() {
    NameIdentifier[] nameIdentifiers =
        GravitinoEnv.getInstance()
            .internalCatalogDispatcher()
            .listCatalogs(Namespace.fromString(searchEntityIdentifier.entityIdent().toString()));
    List<SearchEntitySource> sources = new ArrayList<>();
    for (NameIdentifier nameIdentifier : nameIdentifiers) {
      SearchEntityIdentifier metadata =
          SearchEntityIdentifier.of(nameIdentifier, Entity.EntityType.CATALOG);
      sources.add(new CatalogSearchEntitySource(metadata, true));
    }

    NameIdentifier[] tagIdentifiers =
        Arrays.stream(
                GravitinoEnv.getInstance()
                    .internalTagDispatcher()
                    .listTags(searchEntityIdentifier.metalake()))
            .map(tagName -> NameIdentifierUtil.ofTag(searchEntityIdentifier.metalake(), tagName))
            .toArray(NameIdentifier[]::new);
    sources.add(
        new TagSearchEntitySource(
            SearchEntitySource.toSearchEntityIdentifiers(tagIdentifiers, Entity.EntityType.TAG)));

    AccessControlDispatcher accessControlDispatcher = SearchEntitySource.accessControlDispatcher();
    if (accessControlDispatcher != null) {
      NameIdentifier[] userIdentifiers =
          Arrays.stream(accessControlDispatcher.listUserNames(searchEntityIdentifier.metalake()))
              .map(name -> NameIdentifierUtil.ofUser(searchEntityIdentifier.metalake(), name))
              .toArray(NameIdentifier[]::new);
      sources.add(
          new UserSearchEntitySource(
              SearchEntitySource.toSearchEntityIdentifiers(
                  userIdentifiers, Entity.EntityType.USER)));

      NameIdentifier[] groupIdentifiers =
          Arrays.stream(accessControlDispatcher.listGroupNames(searchEntityIdentifier.metalake()))
              .map(name -> NameIdentifierUtil.ofGroup(searchEntityIdentifier.metalake(), name))
              .toArray(NameIdentifier[]::new);
      sources.add(
          new GroupSearchEntitySource(
              SearchEntitySource.toSearchEntityIdentifiers(
                  groupIdentifiers, Entity.EntityType.GROUP)));

      NameIdentifier[] roleIdentifiers =
          Arrays.stream(accessControlDispatcher.listRoleNames(searchEntityIdentifier.metalake()))
              .map(name -> NameIdentifierUtil.ofRole(searchEntityIdentifier.metalake(), name))
              .toArray(NameIdentifier[]::new);
      sources.add(
          new RoleSearchEntitySource(
              SearchEntitySource.toSearchEntityIdentifiers(
                  roleIdentifiers, Entity.EntityType.ROLE)));
    }
    NameIdentifier[] policyIdentifiers =
        Arrays.stream(
                GravitinoEnv.getInstance()
                    .internalPolicyDispatcher()
                    .listPolicies(searchEntityIdentifier.metalake()))
            .map(
                policyName ->
                    NameIdentifierUtil.ofPolicy(searchEntityIdentifier.metalake(), policyName))
            .toArray(NameIdentifier[]::new);
    sources.add(
        new PolicySearchEntitySource(
            SearchEntitySource.toSearchEntityIdentifiers(
                policyIdentifiers, Entity.EntityType.POLICY)));
    return sources;
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    throw new GravitinoRuntimeException("Should never come here");
  }
}
