/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.Group;

class GroupSearchEntitySource extends LeafSearchEntitySource {

  GroupSearchEntitySource(List<SearchEntityIdentifier> metadataList) {
    super(metadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    AccessControlDispatcher dispatcher = SearchEntitySource.accessControlDispatcher();
    Group group =
        dispatcher.getGroup(
            searchEntityIdentifier.metalake(), searchEntityIdentifier.entityIdent().name());
    return EntityConverterUtils.toGroupSearchEntityPO(group, searchEntityIdentifier.metalake());
  }
}
