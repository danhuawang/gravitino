/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.EntityCombinedView;
import org.apache.gravitino.rel.View;
import org.apache.gravitino.tag.Tag;

class ViewSearchEntitySource extends LeafSearchEntitySource {

  protected ViewSearchEntitySource(List<SearchEntityIdentifier> viewMetadataList) {
    super(viewMetadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    View view =
        GravitinoEnv.getInstance().viewDispatcher().loadView(searchEntityIdentifier.entityIdent());
    Tag[] metadataTags =
        SearchEntitySource.getMetadataObjectTags(
            searchEntityIdentifier.entityIdent(), searchEntityIdentifier.entityType());

    return EntityConverterUtils.toViewSearchEntityPO(
        (EntityCombinedView) view, metadataTags, searchEntityIdentifier.entityIdent());
  }
}
