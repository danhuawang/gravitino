/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.GravitinoEnv;

class JobTemplateSearchEntitySource extends LeafSearchEntitySource {
  JobTemplateSearchEntitySource(List<SearchEntityIdentifier> identifiers) {
    super(identifiers);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier identifier) {
    return EntityConverterUtils.toJobTemplateSearchEntityPO(
        GravitinoEnv.getInstance()
            .internalJobOperationDispatcher()
            .getJobTemplate(identifier.metalake(), identifier.entityIdent().name()),
        identifier.metalake());
  }
}
