/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.function.Function;
import org.apache.gravitino.tag.Tag;

class FunctionSearchEntitySource extends LeafSearchEntitySource {

  protected FunctionSearchEntitySource(List<SearchEntityIdentifier> functionMetadataList) {
    super(functionMetadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    Function function =
        GravitinoEnv.getInstance()
            .internalFunctionDispatcher()
            .getFunction(searchEntityIdentifier.entityIdent());
    Tag[] metadataTags =
        SearchEntitySource.getMetadataObjectTags(
            searchEntityIdentifier.entityIdent(), searchEntityIdentifier.entityType());

    return EntityConverterUtils.toFunctionSearchEntityPO(
        function, metadataTags, searchEntityIdentifier.entityIdent());
  }
}
