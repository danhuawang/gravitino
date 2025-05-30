/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.preview;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import java.util.List;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.tag.TagDispatcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestTrinoJdbcDataPreviewOperator {

  @Test
  public void testExtractSensitiveColumns() {
    Config config = mock(Config.class);
    TagDispatcher dispatcher = mock(TagDispatcher.class);
    when(config.get(DataPreviewConfig.JDBC_URL_CONFIG)).thenReturn("jdbc://xxx");
    when(config.get(DataPreviewConfig.JDBC_DRIVER_CONFIG)).thenReturn("xxx");
    when(config.get(DataPreviewConfig.JDBC_USERNAME_CONFIG)).thenReturn("user");
    when(config.get(DataPreviewConfig.JDBC_PASSWORD_CONFIG)).thenReturn("password");
    when(config.get(DataPreviewConfig.TIMEOUT_CONFIG)).thenReturn(10);
    when(config.get(DataPreviewConfig.MAX_ROW_COUNT_CONFIG)).thenReturn(10);
    when(config.get(DataPreviewConfig.SENSITIVE_TAGS_CONFIG))
        .thenReturn(Lists.newArrayList("test1", "test2"));
    TrinoJdbcDataPreviewOperator operator = new TrinoJdbcDataPreviewOperator(config, dispatcher);
    List<String> sensitiveColumns = Lists.newArrayList();

    // case 1: Extract sensitive columns
    when(dispatcher.listMetadataObjectsForTag("test", "test1"))
        .thenReturn(
            new MetadataObject[] {
              MetadataObjects.of("test.test.test", "a", MetadataObject.Type.COLUMN)
            });
    when(dispatcher.listMetadataObjectsForTag("test", "test2"))
        .thenReturn(
            new MetadataObject[] {
              MetadataObjects.of("test.test.test", "b", MetadataObject.Type.COLUMN)
            });
    operator.extractSensitiveColumns(
        NameIdentifier.of("test", "test", "test", "test"),
        Entity.EntityType.TABLE,
        sensitiveColumns);
    Assertions.assertEquals(2, sensitiveColumns.size());
    Assertions.assertTrue(sensitiveColumns.contains("a"));
    Assertions.assertTrue(sensitiveColumns.contains("b"));

    // case 2: The table is sensitive table because the table has the tag
    when(dispatcher.listMetadataObjectsForTag("test", "test1"))
        .thenReturn(
            new MetadataObject[] {
              MetadataObjects.of("test.test", "test", MetadataObject.Type.TABLE)
            });
    Assertions.assertThrows(
        DataPreviewSensitiveTableException.class,
        () ->
            operator.extractSensitiveColumns(
                NameIdentifier.of("test", "test", "test", "test"),
                Entity.EntityType.TABLE,
                sensitiveColumns));

    // case 3: The table is sensitive table because the schema has the tag
    when(dispatcher.listMetadataObjectsForTag("test", "test1"))
        .thenReturn(
            new MetadataObject[] {MetadataObjects.of("test", "test", MetadataObject.Type.SCHEMA)});
    Assertions.assertThrows(
        DataPreviewSensitiveTableException.class,
        () ->
            operator.extractSensitiveColumns(
                NameIdentifier.of("test", "test", "test", "test"),
                Entity.EntityType.TABLE,
                sensitiveColumns));

    // case 4: The table is sensitive table because the catalog has the tag
    when(dispatcher.listMetadataObjectsForTag("test", "test1"))
        .thenReturn(
            new MetadataObject[] {MetadataObjects.of(null, "test", MetadataObject.Type.CATALOG)});
    Assertions.assertThrows(
        DataPreviewSensitiveTableException.class,
        () ->
            operator.extractSensitiveColumns(
                NameIdentifier.of("test", "test", "test", "test"),
                Entity.EntityType.TABLE,
                sensitiveColumns));
  }
}
