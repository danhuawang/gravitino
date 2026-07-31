/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.preview;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.trino.jdbc.Row;
import io.trino.jdbc.TrinoArray;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Types;
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

  @Test
  public void testGeneratePreviewSQLForMultiMetalakeCatalog() {
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
    Assertions.assertEquals(
        "SELECT * FROM \"test.catalog_postgres\".\"public\".\"my_table\" LIMIT 10",
        operator.generatePreviewSQL(
            NameIdentifier.of("test", "catalog_postgres", "public", "my_table"), 10));
  }

  @Test
  public void testConvertToValue() {
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

    // case 1: Convert to value
    Object value = operator.convertToValue("test", Types.StringType.get());
    Assertions.assertEquals(value, "test");

    // case 2: Convert to value with array type
    List<String> list = Lists.newArrayList("1", "2");

    TrinoArray array = mock(TrinoArray.class);
    when(array.getArray()).thenReturn(list.toArray(new String[0]));
    value = operator.convertToValue(array, Types.ListType.nullable(Types.StringType.get()));
    Assertions.assertEquals(2, ((List<?>) value).size());
    Assertions.assertIterableEquals(list, (List<?>) value);

    // case 3: Convert Map
    Map<String, String> map = Maps.newHashMap();
    map.put("a", "b");
    map.put("b", "c");
    value =
        operator.convertToValue(
            map, Types.MapType.valueNullable(Types.StringType.get(), Types.StringType.get()));
    Assertions.assertEquals(2, ((Map<?, ?>) value).size());
    Assertions.assertTrue(((Map<?, ?>) value).containsKey("a"));
    Assertions.assertTrue(((Map<?, ?>) value).containsKey("b"));

    // case 4: Convert Binary
    byte[] binary = new byte[5];
    for (int i = 0; i < binary.length; i++) {
      binary[i] = (byte) i;
    }
    value = operator.convertToValue(binary, Types.BinaryType.get());
    Assertions.assertEquals("x'0001020304'", value);

    // case 5: Convert date
    String dateString = "2024-01-01";
    Object dateValue = operator.convertToValue(dateString, Types.TimestampType.withTimeZone());
    Assertions.assertEquals("2024-01-01 UTC", dateValue);

    // ISSUE-295: Convert null date
    Assertions.assertNull(operator.convertToValue(null, Types.TimestampType.withTimeZone()));

    // case 6: Convert struct field when JDBC result and Gravitino type cases differ
    Row row = Row.builder().addField("created_at", "2026-07-27 12:00:00").build();
    value =
        operator.convertToValue(
            row,
            Types.StructType.of(
                Types.StructType.Field.nullableField(
                    "CREATED_AT", Types.TimestampType.withTimeZone())));
    Assertions.assertEquals("2026-07-27 12:00:00 UTC", ((Map<?, ?>) value).get("created_at"));
  }

  @Test
  public void testConvertToRecordsMatchesColumnTypesIgnoringCase() throws Exception {
    TrinoJdbcDataPreviewOperator operator = newOperator();

    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(resultSet.getMetaData()).thenReturn(metaData);
    when(metaData.getColumnCount()).thenReturn(2);
    when(metaData.getColumnName(1)).thenReturn("id");
    when(metaData.getColumnName(2)).thenReturn("created_at");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject("id")).thenReturn("1");
    when(resultSet.getObject("created_at")).thenReturn("2026-07-27 12:00:00");

    Column[] columns =
        new Column[] {
          Column.of("ID", Types.StringType.get()),
          Column.of("CREATED_AT", Types.TimestampType.withTimeZone())
        };

    Map<String, Object>[] records =
        operator.convertToRecords(resultSet, 10, Lists.newArrayList(), columns);

    Assertions.assertEquals(1, records.length);
    Assertions.assertEquals("1", records[0].get("id"));
    Assertions.assertEquals("2026-07-27 12:00:00 UTC", records[0].get("created_at"));
  }

  @Test
  public void testConvertToRecordsMasksSensitiveColumnsIgnoringCase() throws Exception {
    TrinoJdbcDataPreviewOperator operator = newOperator();

    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(resultSet.getMetaData()).thenReturn(metaData);
    when(metaData.getColumnCount()).thenReturn(1);
    when(metaData.getColumnName(1)).thenReturn("created_at");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject("created_at")).thenReturn("2026-07-27 12:00:00");

    Column[] columns = new Column[] {Column.of("CREATED_AT", Types.TimestampType.withTimeZone())};

    Map<String, Object>[] records =
        operator.convertToRecords(resultSet, 10, Lists.newArrayList("CREATED_AT"), columns);

    Assertions.assertEquals(1, records.length);
    Assertions.assertEquals("*", records[0].get("created_at"));
  }

  @Test
  public void testConvertToRecordsKeepsValueWhenColumnTypeIsMissing() throws Exception {
    TrinoJdbcDataPreviewOperator operator = newOperator();

    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(resultSet.getMetaData()).thenReturn(metaData);
    when(metaData.getColumnCount()).thenReturn(1);
    when(metaData.getColumnName(1)).thenReturn("preview_only");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject("preview_only")).thenReturn("value");

    Map<String, Object>[] records =
        operator.convertToRecords(resultSet, 10, Lists.newArrayList(), new Column[0]);

    Assertions.assertEquals(1, records.length);
    Assertions.assertEquals("value", records[0].get("preview_only"));
  }

  @Test
  public void testConvertToRecordsUsesExactColumnNameBeforeIgnoringCase() throws Exception {
    TrinoJdbcDataPreviewOperator operator = newOperator();

    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(resultSet.getMetaData()).thenReturn(metaData);
    when(metaData.getColumnCount()).thenReturn(1);
    when(metaData.getColumnName(1)).thenReturn("CREATED_AT");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject("CREATED_AT")).thenReturn("2026-07-27 12:00:00");

    Column[] columns =
        new Column[] {
          Column.of("created_at", Types.TimestampType.withTimeZone()),
          Column.of("CREATED_AT", Types.StringType.get())
        };

    Map<String, Object>[] records =
        operator.convertToRecords(resultSet, 10, Lists.newArrayList(), columns);

    Assertions.assertEquals("2026-07-27 12:00:00", records[0].get("CREATED_AT"));
  }

  @Test
  public void testConvertToValueUsesExactStructFieldNameBeforeIgnoringCase() {
    TrinoJdbcDataPreviewOperator operator = newOperator();
    Row row = Row.builder().addField("CREATED_AT", "2026-07-27 12:00:00").build();

    Types.StructType structType =
        Types.StructType.of(
            Types.StructType.Field.nullableField("created_at", Types.TimestampType.withTimeZone()),
            Types.StructType.Field.nullableField("CREATED_AT", Types.StringType.get()));

    Map<?, ?> convertedRow = (Map<?, ?>) operator.convertToValue(row, structType);

    Assertions.assertEquals("2026-07-27 12:00:00", convertedRow.get("CREATED_AT"));
  }

  @Test
  public void testConvertToRecordsFailsOnAmbiguousColumnNameIgnoringCase() throws Exception {
    TrinoJdbcDataPreviewOperator operator = newOperator();

    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(resultSet.getMetaData()).thenReturn(metaData);
    when(metaData.getColumnCount()).thenReturn(1);
    when(metaData.getColumnName(1)).thenReturn("Created_At");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject("Created_At")).thenReturn("2026-07-27 12:00:00");

    Column[] columns =
        new Column[] {
          Column.of("created_at", Types.TimestampType.withTimeZone()),
          Column.of("CREATED_AT", Types.StringType.get())
        };

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> operator.convertToRecords(resultSet, 10, Lists.newArrayList(), columns));
    Assertions.assertTrue(
        exception.getMessage().contains("Ambiguous column name ignoring case: Created_At"));
  }

  @Test
  public void testConvertToValueFailsOnAmbiguousStructFieldNameIgnoringCase() {
    TrinoJdbcDataPreviewOperator operator = newOperator();
    Row row = Row.builder().addField("Created_At", "2026-07-27 12:00:00").build();

    Types.StructType structType =
        Types.StructType.of(
            Types.StructType.Field.nullableField("created_at", Types.TimestampType.withTimeZone()),
            Types.StructType.Field.nullableField("CREATED_AT", Types.StringType.get()));

    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class, () -> operator.convertToValue(row, structType));
    Assertions.assertTrue(
        exception.getMessage().contains("Ambiguous field name ignoring case: Created_At"));
  }

  private TrinoJdbcDataPreviewOperator newOperator() {
    Config config = mock(Config.class);
    TagDispatcher dispatcher = mock(TagDispatcher.class);
    when(config.get(DataPreviewConfig.JDBC_URL_CONFIG)).thenReturn("jdbc://xxx");
    when(config.get(DataPreviewConfig.JDBC_DRIVER_CONFIG)).thenReturn("xxx");
    when(config.get(DataPreviewConfig.JDBC_USERNAME_CONFIG)).thenReturn("user");
    when(config.get(DataPreviewConfig.JDBC_PASSWORD_CONFIG)).thenReturn("password");
    when(config.get(DataPreviewConfig.TIMEOUT_CONFIG)).thenReturn(10);
    when(config.get(DataPreviewConfig.MAX_ROW_COUNT_CONFIG)).thenReturn(10);
    when(config.get(DataPreviewConfig.SENSITIVE_TAGS_CONFIG)).thenReturn(Lists.newArrayList());
    return new TrinoJdbcDataPreviewOperator(config, dispatcher);
  }
}
