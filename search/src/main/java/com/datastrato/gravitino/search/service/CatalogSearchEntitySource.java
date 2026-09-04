/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.ArrayList;
import java.util.List;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.connector.BaseCatalog;
import org.apache.gravitino.tag.Tag;

class CatalogSearchEntitySource extends ParentEntitySource {
  private Catalog.Type catalogType;

  protected CatalogSearchEntitySource(
      SearchEntityIdentifier searchEntityIdentifier, boolean cascading) {
    super(searchEntityIdentifier, cascading);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    Catalog catalog = getCatalog(searchEntityIdentifier.entityIdent());
    Tag[] metadataTags =
        SearchEntitySource.getMetadataObjectTags(
            searchEntityIdentifier.entityIdent(), searchEntityIdentifier.entityType());
    this.catalogType = catalog.type();
    return EntityConverterUtils.toCatalogSearchEntityPO(
        (BaseCatalog<? extends BaseCatalog<?>>) catalog,
        metadataTags,
        searchEntityIdentifier.entityIdent());
  }

  private static Catalog getCatalog(NameIdentifier nameIdentifier) {
    return GravitinoEnv.getInstance().internalCatalogDispatcher().loadCatalog(nameIdentifier);
  }

  static Catalog.Type getCatalogType(NameIdentifier nameIdentifier) {
    Catalog catalog = getCatalog(nameIdentifier);
    return catalog.type();
  }

  @Override
  protected List<SearchEntitySource> createChildEntitySources() {
    NameIdentifier[] nameIdentifiers =
        GravitinoEnv.getInstance()
            .internalSchemaDispatcher()
            .listSchemas(Namespace.fromString(searchEntityIdentifier.entityIdent().toString()));
    List<SearchEntitySource> sources = new ArrayList<>();
    for (NameIdentifier nameIdentifier : nameIdentifiers) {
      SearchEntityIdentifier metadata =
          SearchEntityIdentifier.of(nameIdentifier, Entity.EntityType.SCHEMA);
      sources.add(new SchemaSearchEntitySource(metadata, true, catalogType));
    }
    return sources;
  }
}
