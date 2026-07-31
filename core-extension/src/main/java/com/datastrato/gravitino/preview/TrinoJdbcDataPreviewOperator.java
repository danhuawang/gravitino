/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.preview;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.trino.jdbc.Row;
import io.trino.jdbc.RowField;
import io.trino.jdbc.TrinoArray;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.pool2.impl.BaseObjectPoolConfig;
import org.apache.gravitino.Config;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.NoSuchTagException;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Currently, Gravitino use Trino as the unified solution to data preview. Trino will run as the
 * admin user and use Gravitino connector to query the sample data. If the query exceed the timeout,
 * the operator will throw a runtime exception. If sensitive data tags associate with columns, the
 * result will mask the columns. If sensitive data tags with the table, schema, catalog, it will
 * throw a data preview sensitive exception.
 */
public class TrinoJdbcDataPreviewOperator {
  private static final Logger LOG = LoggerFactory.getLogger(TrinoJdbcDataPreviewOperator.class);
  private final BasicDataSource dataSource;
  private final int timeoutSec;
  private final int maxRowCount;
  private final List<String> sensitiveTags;
  private final TagDispatcher tagDispatcher;

  public TrinoJdbcDataPreviewOperator(Config config, TagDispatcher tagDispatcher) {
    this.dataSource = new BasicDataSource();
    String jdbcUrl = config.get(DataPreviewConfig.JDBC_URL_CONFIG);
    dataSource.setUrl(jdbcUrl);
    dataSource.setDriverClassName(config.get(DataPreviewConfig.JDBC_DRIVER_CONFIG));
    dataSource.setUsername(config.get(DataPreviewConfig.JDBC_USERNAME_CONFIG));
    String password = config.get(DataPreviewConfig.JDBC_PASSWORD_CONFIG);
    if (password != null) {
      dataSource.setPassword(password);
    }

    dataSource.setDefaultAutoCommit(true);
    dataSource.setMaxTotal(20);
    dataSource.setMaxIdle(5);
    dataSource.setMinIdle(0);
    dataSource.setLogAbandoned(true);
    dataSource.setRemoveAbandonedOnBorrow(true);
    dataSource.setTestOnBorrow(BaseObjectPoolConfig.DEFAULT_TEST_ON_BORROW);
    dataSource.setTestWhileIdle(BaseObjectPoolConfig.DEFAULT_TEST_WHILE_IDLE);
    dataSource.setNumTestsPerEvictionRun(BaseObjectPoolConfig.DEFAULT_NUM_TESTS_PER_EVICTION_RUN);
    dataSource.setTestOnReturn(false);
    dataSource.setLifo(BaseObjectPoolConfig.DEFAULT_LIFO);
    this.timeoutSec = config.get(DataPreviewConfig.TIMEOUT_CONFIG);
    this.maxRowCount = config.get(DataPreviewConfig.MAX_ROW_COUNT_CONFIG);
    this.sensitiveTags = config.get(DataPreviewConfig.SENSITIVE_TAGS_CONFIG);
    this.tagDispatcher = tagDispatcher;
  }

  public Map<String, Object>[] preview(
      NameIdentifier ident, Entity.EntityType type, int limit, Column[] columns) {
    try (Connection connection = getConnection()) {
      Statement statement = connection.createStatement();
      statement.setQueryTimeout(timeoutSec);
      int realRowCount = Math.min(limit, maxRowCount);
      ResultSet result = statement.executeQuery(generatePreviewSQL(ident, realRowCount));
      List<String> sensitiveColumns = Lists.newArrayList();

      extractSensitiveColumns(ident, type, sensitiveColumns);

      return convertToRecords(result, realRowCount, sensitiveColumns, columns);
    } catch (SQLException se) {
      throw new RuntimeException(se);
    }
  }

  @VisibleForTesting
  void extractSensitiveColumns(
      NameIdentifier ident, Entity.EntityType type, List<String> sensitiveColumns) {
    String tableFullName = NameIdentifierUtil.toMetadataObject(ident, type).fullName();
    NameIdentifier schemaIdent = NameIdentifier.of(ident.namespace().levels());
    String schemaFullName =
        NameIdentifierUtil.toMetadataObject(schemaIdent, Entity.EntityType.SCHEMA).fullName();
    String catalogFullName =
        NameIdentifierUtil.toMetadataObject(
                NameIdentifier.of(schemaIdent.namespace().levels()), Entity.EntityType.CATALOG)
            .fullName();

    for (String tag : sensitiveTags) {
      try {
        MetadataObject[] metadataObjects =
            tagDispatcher.listMetadataObjectsForTag(ident.namespace().level(0), tag);
        for (MetadataObject metadataObject : metadataObjects) {
          if (metadataObject.type() == MetadataObject.Type.COLUMN
              && Objects.equals(metadataObject.parent(), tableFullName)) {
            sensitiveColumns.add(metadataObject.name());
          } else if ((metadataObject.type() == MetadataObject.Type.TABLE
                  && Objects.equals(metadataObject.fullName(), tableFullName))
              || (metadataObject.type() == MetadataObject.Type.SCHEMA
                  && Objects.equals(metadataObject.fullName(), schemaFullName))
              || (metadataObject.type() == MetadataObject.Type.CATALOG
                  && Objects.equals(metadataObject.fullName(), catalogFullName))) {
            throw new DataPreviewSensitiveTableException(
                String.format(
                    "Table %s is a table contains sensitive data, you can't preview it",
                    tableFullName));
          }
        }
      } catch (NoSuchTagException nse) {
        LOG.warn("The tag {} doesn't exist in the metalake {}", tag, ident.namespace().level(0));
      }
    }
  }

  @VisibleForTesting
  String generatePreviewSQL(NameIdentifier identifier, int limit) {
    NameIdentifier catalogIdent = NameIdentifierUtil.getCatalogIdentifier(identifier);
    String tableName = identifier.name();
    String schemaName = NameIdentifier.of(identifier.namespace().levels()).name();

    return String.format(
        "SELECT * FROM \"%s\".\"%s\".\"%s\" LIMIT %d", catalogIdent, schemaName, tableName, limit);
  }

  private Connection getConnection() throws SQLException {
    return dataSource.getConnection();
  }

  @VisibleForTesting
  Map<String, Object>[] convertToRecords(
      ResultSet resultSet, int limit, List<String> sensitiveColumns, Column[] columns)
      throws SQLException {
    ResultSetMetaData metaData = resultSet.getMetaData();
    int numCols = metaData.getColumnCount();
    List<String> colNames = Lists.newArrayList();
    for (int colIndex = 0; colIndex < numCols; colIndex++) {
      colNames.add(metaData.getColumnName(colIndex + 1));
    }

    TypeNameLookup columnTypes = new TypeNameLookup("column");
    for (Column column : columns) {
      columnTypes.put(column.name(), column.dataType());
    }

    NameLookup sensitiveColumnNames = new NameLookup();
    for (String sensitiveColumn : sensitiveColumns) {
      sensitiveColumnNames.add(sensitiveColumn);
    }

    List<Map<String, Object>> recordList = Lists.newArrayList();

    int recordIndex = 0;
    while (resultSet.next() && recordIndex < limit) {
      Map<String, Object> record = Maps.newHashMap();
      for (String colName : colNames) {
        if (sensitiveColumnNames.contains(colName)) {
          record.put(colName, "*");
        } else {
          Type type = columnTypes.get(colName);
          record.put(colName, convertToValue(resultSet.getObject(colName), type));
        }
      }

      recordList.add(record);
      recordIndex++;
    }

    Map<String, Object>[] records = new HashMap[recordList.size()];
    recordIndex = 0;
    for (Map<String, Object> record : recordList) {
      records[recordIndex] = record;
      recordIndex++;
    }

    return records;
  }

  private static String normalizeName(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  private static class NameLookup {
    private final Set<String> exactNames = Sets.newHashSet();
    private final Set<String> caseInsensitiveNames = Sets.newHashSet();

    private void add(String name) {
      exactNames.add(name);
      caseInsensitiveNames.add(normalizeName(name));
    }

    private boolean contains(String name) {
      if (exactNames.contains(name)) {
        return true;
      }
      return caseInsensitiveNames.contains(normalizeName(name));
    }
  }

  private static class TypeNameLookup {
    private final String nameKind;
    private final Map<String, Type> exactTypes = Maps.newHashMap();
    private final Map<String, Type> caseInsensitiveTypes = Maps.newHashMap();
    private final Map<String, String> caseInsensitiveNames = Maps.newHashMap();
    private final Set<String> ambiguousNames = Sets.newHashSet();

    private TypeNameLookup(String nameKind) {
      this.nameKind = nameKind;
    }

    private void put(String name, Type type) {
      exactTypes.put(name, type);
      String normalizedName = normalizeName(name);
      String existingName = caseInsensitiveNames.putIfAbsent(normalizedName, name);
      if (existingName == null || existingName.equals(name)) {
        caseInsensitiveTypes.put(normalizedName, type);
      } else {
        ambiguousNames.add(normalizedName);
      }
    }

    private Type get(String name) {
      Type type = exactTypes.get(name);
      if (type != null) {
        return type;
      }

      String normalizedName = normalizeName(name);
      if (ambiguousNames.contains(normalizedName)) {
        throw new IllegalArgumentException(
            String.format("Ambiguous %s name ignoring case: %s", nameKind, name));
      }
      return caseInsensitiveTypes.get(normalizedName);
    }
  }

  @VisibleForTesting
  Object convertToValue(@Nullable Object object, @Nullable Type type) {
    if (object == null) {
      return null;
    } else if (object instanceof byte[]) {
      return String.format("x'%s'", Hex.encodeHexString((byte[]) object));
    } else if (type == null) {
      return object;
    } else if (object instanceof TrinoArray) {
      Preconditions.checkArgument(
          type.name() == Type.Name.LIST, "Expected type to be a list, but got: %s", type.name());
      TrinoArray trinoArray = (TrinoArray) object;
      List<Object> values = Lists.newArrayList();
      for (Object value : (Object[]) trinoArray.getArray()) {
        values.add(convertToValue(value, ((Types.ListType) type).elementType()));
      }
      return values;
    } else if (object instanceof Map) {
      Preconditions.checkArgument(
          type.name() == Type.Name.MAP, "Expected type to be a struct, but got: %s", type.name());
      Map<Object, Object> map = (Map<Object, Object>) object;
      Map<Object, Object> convertedMap = Maps.newHashMap();
      Types.MapType mapType = (Types.MapType) type;
      for (Object key : map.keySet()) {
        convertedMap.put(
            convertToValue(key, mapType.keyType()),
            convertToValue(map.get(key), mapType.valueType()));
      }
      return convertedMap;
    } else if (object instanceof Row) {
      Preconditions.checkArgument(
          type.name() == Type.Name.STRUCT,
          "Expected type to be a struct, but got: %s",
          type.name());
      Row row = (Row) object;
      Map<String, Object> convertedRow = Maps.newHashMap();
      TypeNameLookup fieldTypes = new TypeNameLookup("field");
      Types.StructType structType = (Types.StructType) type;
      for (Types.StructType.Field field : structType.fields()) {
        fieldTypes.put(field.name(), field.type());
      }
      for (RowField field : row.getFields()) {
        field
            .getName()
            .ifPresent(
                name -> {
                  convertedRow.put(name, convertToValue(field.getValue(), fieldTypes.get(name)));
                });
      }
      return convertedRow;
    } else if (type.name() == Type.Name.TIMESTAMP && ((Types.TimestampType) type).hasTimeZone()) {
      return String.format("%s UTC", object.toString());
    } else {
      return object;
    }
  }
}
