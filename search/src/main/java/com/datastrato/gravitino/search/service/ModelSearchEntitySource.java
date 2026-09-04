/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.po.SearchModelEntityPO.SearchModelVersionPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.gravitino.Auditable;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.EntityCombinedModel;
import org.apache.gravitino.model.ModelVersion;
import org.apache.gravitino.tag.Tag;

class ModelSearchEntitySource extends LeafSearchEntitySource {

  protected ModelSearchEntitySource(List<SearchEntityIdentifier> modelMetadataList) {
    super(modelMetadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    Auditable modelAuditable =
        GravitinoEnv.getInstance()
            .internalModelDispatcher()
            .getModel(searchEntityIdentifier.entityIdent());
    Tag[] metadataTags =
        SearchEntitySource.getMetadataObjectTags(
            searchEntityIdentifier.entityIdent(), searchEntityIdentifier.entityType());
    List<SearchModelVersionPO> modelVersionPOS = getModelVersions(searchEntityIdentifier);

    return EntityConverterUtils.toModelSearchEntityPO(
        (EntityCombinedModel) modelAuditable,
        metadataTags,
        searchEntityIdentifier.entityIdent(),
        modelVersionPOS);
  }

  private List<SearchModelVersionPO> getModelVersions(
      SearchEntityIdentifier searchEntityIdentifier) {
    ModelVersion[] modelVersions =
        GravitinoEnv.getInstance()
            .internalModelDispatcher()
            .listModelVersionInfos(searchEntityIdentifier.entityIdent());
    if (modelVersions == null || modelVersions.length == 0) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<SearchModelVersionPO> builder = ImmutableList.builder();
    for (ModelVersion modelVersion : modelVersions) {
      builder.add(
          new SearchModelVersionPO.Builder()
              .withAliases(ImmutableList.copyOf(modelVersion.aliases()))
              .withVersion(modelVersion.version())
              .withUri(ImmutableList.sortedCopyOf(modelVersion.uris().values()))
              .build());
    }

    return builder.build();
  }
}
