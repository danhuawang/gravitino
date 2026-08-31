/*
 * Copyright 2024 Datastrato Inc.
 */

package com.datastrato.gravitino.lineage;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.openlineage.client.OpenLineage;
import io.openlineage.server.OpenLineage.RunEvent;
import org.apache.gravitino.server.web.ObjectMapperProvider;

public class RunEventUtils {

  public static OpenLineage.RunEvent getClientRunEvent(RunEvent event)
      throws JsonProcessingException {
    String value = ObjectMapperProvider.objectMapper().writeValueAsString(event);
    return ObjectMapperProvider.objectMapper().readValue(value, OpenLineage.RunEvent.class);
  }
}
