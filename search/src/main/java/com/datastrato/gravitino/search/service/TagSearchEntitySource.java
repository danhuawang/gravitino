/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.tag.Tag;

/**
 * Produces one indexed document per tag definition, the same way {@link TableSearchEntitySource}
 * and the other leaf sources do for their entity type.
 *
 * <p>Not to be confused with {@link TagAssociationSearchEntitySource}, which carried this name
 * before tags became indexable: that one produces no tag document at all, it re-syncs the entities
 * a tag is attached to.
 */
class TagSearchEntitySource extends LeafSearchEntitySource {

  TagSearchEntitySource(List<SearchEntityIdentifier> metadataList) {
    super(metadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    Tag tag =
        GravitinoEnv.getInstance()
            .tagDispatcher()
            .getTag(searchEntityIdentifier.metalake(), searchEntityIdentifier.entityIdent().name());
    return EntityConverterUtils.toTagSearchEntityPO(tag, searchEntityIdentifier.entityIdent());
  }
}
