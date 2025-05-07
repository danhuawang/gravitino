/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.Auditable;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.EntityCombinedModel;
import org.apache.gravitino.tag.Tag;

class ModelSearchEntitySource extends LeafSearchEntitySource {

  protected ModelSearchEntitySource(List<SearchEntityIdentifier> modelMetadataList) {
    super(modelMetadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    Auditable modelAuditable =
        GravitinoEnv.getInstance().modelDispatcher().getModel(searchEntityIdentifier.entityIdent());
    Tag[] metadataTags =
        SearchEntitySource.getMetadataObjectTags(
            searchEntityIdentifier.entityIdent(), searchEntityIdentifier.entityType());
    return EntityConverterUtils.toModelSearchEntityPO(
        (EntityCombinedModel) modelAuditable, metadataTags, searchEntityIdentifier.entityIdent());
  }
}
