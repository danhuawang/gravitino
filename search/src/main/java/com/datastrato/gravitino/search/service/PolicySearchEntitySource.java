/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.meta.PolicyEntity;

/** A search entity source that loads policy definitions. */
class PolicySearchEntitySource extends LeafSearchEntitySource {

  PolicySearchEntitySource(List<SearchEntityIdentifier> metadataList) {
    super(metadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    PolicyEntity policy =
        GravitinoEnv.getInstance()
            .internalPolicyDispatcher()
            .getPolicy(
                searchEntityIdentifier.metalake(), searchEntityIdentifier.entityIdent().name());
    return EntityConverterUtils.toPolicySearchEntityPO(
        policy, searchEntityIdentifier.entityIdent());
  }
}
