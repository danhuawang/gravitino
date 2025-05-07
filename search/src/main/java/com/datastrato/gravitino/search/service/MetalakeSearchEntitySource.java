/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import java.util.ArrayList;
import java.util.List;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;

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
            .catalogDispatcher()
            .listCatalogs(Namespace.fromString(searchEntityIdentifier.entityIdent().toString()));
    List<SearchEntitySource> sources = new ArrayList<>();
    for (NameIdentifier nameIdentifier : nameIdentifiers) {
      SearchEntityIdentifier metadata =
          SearchEntityIdentifier.of(nameIdentifier, Entity.EntityType.CATALOG);
      sources.add(new CatalogSearchEntitySource(metadata, true));
    }
    return sources;
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    throw new GravitinoRuntimeException("Should never come here");
  }
}
