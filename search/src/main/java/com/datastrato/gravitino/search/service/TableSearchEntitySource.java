/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.Auditable;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.EntityCombinedTable;
import org.apache.gravitino.tag.Tag;

class TableSearchEntitySource extends LeafSearchEntitySource {

  protected TableSearchEntitySource(List<SearchEntityIdentifier> tableMetadataList) {
    super(tableMetadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    Auditable tableAuditable =
        GravitinoEnv.getInstance()
            .tableDispatcher()
            .loadTable(searchEntityIdentifier.entityIdent());

    EntityCombinedTable table = (EntityCombinedTable) tableAuditable;
    // We need to reload the table if the table is not created by Gravitino.
    if (table.tableFromGravitino() == null) {
      tableAuditable =
          GravitinoEnv.getInstance()
              .tableDispatcher()
              .loadTable(searchEntityIdentifier.entityIdent());
    }
    Tag[] metadataTags =
        SearchEntitySource.getMetadataObjectTags(
            searchEntityIdentifier.entityIdent(), searchEntityIdentifier.entityType());
    return EntityConverterUtils.toTableSearchEntityPO(
        (EntityCombinedTable) tableAuditable, metadataTags, searchEntityIdentifier.entityIdent());
  }
}
