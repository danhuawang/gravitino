/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.preview.DataPreviewConfig;
import org.apache.gravitino.Config;
import org.apache.gravitino.tag.TagDispatcher;
import org.junit.jupiter.api.Test;

public class TestDatastratoGravitinoEnvDataPreview {

  @Test
  public void testDisabledPreviewDoesNotReadJdbcUrl() {
    Config config = mock(Config.class);
    when(config.get(DataPreviewConfig.ENABLED_CONFIG)).thenReturn(false);

    assertTrue(
        DatastratoGravitinoEnv.createDataPreviewOperator(config, mock(TagDispatcher.class))
            .isEmpty());
    verify(config, never()).get(DataPreviewConfig.JDBC_URL_CONFIG);
  }
}
