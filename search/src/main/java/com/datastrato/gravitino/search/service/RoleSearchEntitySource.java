/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.Role;

class RoleSearchEntitySource extends LeafSearchEntitySource {

  RoleSearchEntitySource(List<SearchEntityIdentifier> metadataList) {
    super(metadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    AccessControlDispatcher dispatcher = SearchEntitySource.accessControlDispatcher();
    Role role =
        dispatcher.getRole(
            searchEntityIdentifier.metalake(), searchEntityIdentifier.entityIdent().name());
    return EntityConverterUtils.toRoleSearchEntityPO(role, searchEntityIdentifier.metalake());
  }
}
