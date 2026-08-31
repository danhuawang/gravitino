/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.Auditable;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.EntityCombinedFileset;
import org.apache.gravitino.tag.Tag;

class FilesetSearchEntitySource extends LeafSearchEntitySource {

  protected FilesetSearchEntitySource(List<SearchEntityIdentifier> filesMetetadataList) {
    super(filesMetetadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    Auditable filesetAuditable =
        GravitinoEnv.getInstance()
            .filesetDispatcher()
            .loadFileset(searchEntityIdentifier.entityIdent());
    Tag[] metadataTags =
        SearchEntitySource.getMetadataObjectTags(
            searchEntityIdentifier.entityIdent(), searchEntityIdentifier.entityType());
    return EntityConverterUtils.toFilesetSearchEntityPO(
        (EntityCombinedFileset) filesetAuditable,
        metadataTags,
        searchEntityIdentifier.entityIdent());
  }
}
