/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.Auditable;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.EntityCombinedSchema;
import org.apache.gravitino.tag.Tag;

class SchemaSearchEntitySource extends ParentEntitySource {

  private final Catalog.Type catalogType;

  protected SchemaSearchEntitySource(
      SearchEntityIdentifier searchEntityIdentifier, boolean cascading, Catalog.Type catalogType) {
    super(searchEntityIdentifier, cascading);
    this.catalogType = catalogType;
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    Auditable auditable =
        GravitinoEnv.getInstance()
            .schemaDispatcher()
            .loadSchema(searchEntityIdentifier.entityIdent());

    EntityCombinedSchema schema = (EntityCombinedSchema) auditable;
    // We need to reload the schema if the schema is not created by Gravitino. If the schema is
    // not created by Gravitino, the schemaEntity will be null when first loaded.
    if (schema.schemaEntity() == null) {
      auditable =
          GravitinoEnv.getInstance()
              .schemaDispatcher()
              .loadSchema(searchEntityIdentifier.entityIdent());
    }
    Tag[] metadataTags =
        SearchEntitySource.getMetadataObjectTags(
            searchEntityIdentifier.entityIdent(), searchEntityIdentifier.entityType());
    return EntityConverterUtils.toSchemaSearchEntityPO(
        (EntityCombinedSchema) auditable, metadataTags, searchEntityIdentifier.entityIdent());
  }

  @Override
  protected List<SearchEntitySource> createChildEntitySources() {
    return SearchEntitySource.createSearchEntitySourceBySchema(
        searchEntityIdentifier.entityIdent(), catalogType);
  }
}
