/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.search.service;

import com.datastrato.gravitino.search.po.SearchEntityPO;
import com.datastrato.gravitino.search.utils.EntityConverterUtils;
import java.util.List;
import org.apache.gravitino.Auditable;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.EntityCombinedTopic;
import org.apache.gravitino.tag.Tag;

class TopicSearchEntitySource extends LeafSearchEntitySource {

  protected TopicSearchEntitySource(List<SearchEntityIdentifier> topicMetadataList) {
    super(topicMetadataList);
  }

  @Override
  protected SearchEntityPO getSearchEntityPO(SearchEntityIdentifier searchEntityIdentifier) {
    Auditable topicAuditable =
        GravitinoEnv.getInstance()
            .topicDispatcher()
            .loadTopic(searchEntityIdentifier.entityIdent());

    EntityCombinedTopic topic = (EntityCombinedTopic) topicAuditable;
    // We need to reload the topic if the topic is not created by Gravitino.
    if (topic.topicEntity() == null) {
      topicAuditable =
          GravitinoEnv.getInstance()
              .topicDispatcher()
              .loadTopic(searchEntityIdentifier.entityIdent());
    }
    Tag[] metadataTags =
        SearchEntitySource.getMetadataObjectTags(
            searchEntityIdentifier.entityIdent(), searchEntityIdentifier.entityType());
    return EntityConverterUtils.toTopicSearchEntityPO(
        (EntityCombinedTopic) topicAuditable, metadataTags, searchEntityIdentifier.entityIdent());
  }
}
