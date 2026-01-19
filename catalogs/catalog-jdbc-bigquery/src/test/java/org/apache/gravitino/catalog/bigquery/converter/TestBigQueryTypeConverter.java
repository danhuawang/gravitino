package org.apache.gravitino.catalog.bigquery.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BigQueryTypeConverter}. */
public class TestBigQueryTypeConverter {

  private BigQueryTypeConverter typeConverter;

  @BeforeEach
  void setUp() {
    typeConverter = new BigQueryTypeConverter();
  }

  @Test
  void testFromGravitinoBasicTypes() {
    // Boolean
    assertEquals("bool", typeConverter.fromGravitino(Types.BooleanType.get()));

    // Integer types - all map to INT64
    assertEquals("int64", typeConverter.fromGravitino(Types.ByteType.get()));
    assertEquals("int64", typeConverter.fromGravitino(Types.ShortType.get()));
    assertEquals("int64", typeConverter.fromGravitino(Types.IntegerType.get()));
    assertEquals("int64", typeConverter.fromGravitino(Types.LongType.get()));

    // Float types - all map to FLOAT64
    assertEquals("float64", typeConverter.fromGravitino(Types.FloatType.get()));
    assertEquals("float64", typeConverter.fromGravitino(Types.DoubleType.get()));

    // String types
    assertEquals("string", typeConverter.fromGravitino(Types.StringType.get()));
    assertEquals("string", typeConverter.fromGravitino(Types.VarCharType.of(100)));
    assertEquals("string", typeConverter.fromGravitino(Types.FixedCharType.of(10)));

    // Binary
    assertEquals("bytes", typeConverter.fromGravitino(Types.BinaryType.get()));

    // Date
    assertEquals("date", typeConverter.fromGravitino(Types.DateType.get()));
  }

  @Test
  void testFromGravitinoTimeTypes() {
    // Time without precision
    assertEquals("time", typeConverter.fromGravitino(Types.TimeType.get()));

    // Time with precision
    assertEquals("time(6)", typeConverter.fromGravitino(Types.TimeType.of(6)));

    // Timestamp without timezone (DATETIME in BigQuery)
    assertEquals("datetime", typeConverter.fromGravitino(Types.TimestampType.withoutTimeZone()));

    // Timestamp with timezone (TIMESTAMP in BigQuery)
    assertEquals("timestamp", typeConverter.fromGravitino(Types.TimestampType.withTimeZone()));

    // Timestamp with precision
    assertEquals(
        "datetime(3)", typeConverter.fromGravitino(Types.TimestampType.withoutTimeZone(3)));
    assertEquals("timestamp(6)", typeConverter.fromGravitino(Types.TimestampType.withTimeZone(6)));
  }

  @Test
  void testFromGravitinoDecimalTypes() {
    // Standard precision - use NUMERIC (BigQuery NUMERIC supports precision 1-38, scale 0-9 or
    // 0-precision)
    assertEquals("NUMERIC(10, 2)", typeConverter.fromGravitino(Types.DecimalType.of(10, 2)));
    assertEquals("NUMERIC(38, 9)", typeConverter.fromGravitino(Types.DecimalType.of(38, 9)));
    assertEquals("NUMERIC(38, 2)", typeConverter.fromGravitino(Types.DecimalType.of(38, 2)));

    // Note: Gravitino DecimalType is limited to precision 38
    // For BIGNUMERIC, use ExternalType or UnparsedType
  }

  @Test
  void testFromGravitinoUnparsedType() {
    // Test unparsed types from Web UI - only the exact types that are in the Web UI list
    assertEquals("bool", typeConverter.fromGravitino(Types.UnparsedType.of("BOOL")));
    assertEquals("int64", typeConverter.fromGravitino(Types.UnparsedType.of("INT64")));
    assertEquals("float64", typeConverter.fromGravitino(Types.UnparsedType.of("FLOAT64")));
    assertEquals("string", typeConverter.fromGravitino(Types.UnparsedType.of("STRING")));
    assertEquals("bytes", typeConverter.fromGravitino(Types.UnparsedType.of("BYTES")));
    assertEquals("date", typeConverter.fromGravitino(Types.UnparsedType.of("DATE")));
    assertEquals("time", typeConverter.fromGravitino(Types.UnparsedType.of("TIME")));
    assertEquals("datetime", typeConverter.fromGravitino(Types.UnparsedType.of("DATETIME")));
    assertEquals("timestamp", typeConverter.fromGravitino(Types.UnparsedType.of("TIMESTAMP")));
    assertEquals("numeric", typeConverter.fromGravitino(Types.UnparsedType.of("NUMERIC")));
    assertEquals("bignumeric", typeConverter.fromGravitino(Types.UnparsedType.of("BIGNUMERIC")));

    // Test complete complex types - these should be returned as-is (from API calls)
    assertEquals(
        "array<string>", typeConverter.fromGravitino(Types.UnparsedType.of("array<string>")));
    assertEquals(
        "array<int64>", typeConverter.fromGravitino(Types.UnparsedType.of("array<int64>")));
    assertEquals(
        "struct<name string, age int64>",
        typeConverter.fromGravitino(Types.UnparsedType.of("struct<name string, age int64>")));
    assertEquals("range<date>", typeConverter.fromGravitino(Types.UnparsedType.of("range<date>")));
  }

  @Test
  void testFromGravitinoExternalType() {
    assertEquals(
        "array<string>", typeConverter.fromGravitino(Types.ExternalType.of("array<string>")));
    assertEquals("geography", typeConverter.fromGravitino(Types.ExternalType.of("geography")));
    assertEquals("json", typeConverter.fromGravitino(Types.ExternalType.of("json")));
  }

  @Test
  void testFromGravitinoUnsupportedType() {
    // Test with a type that doesn't have a mapping
    assertThrows(
        IllegalArgumentException.class, () -> typeConverter.fromGravitino(Types.NullType.get()));
  }

  @Test
  void testToGravitinoBasicTypes() {
    // Boolean
    assertEquals(
        Types.BooleanType.get(),
        typeConverter.toGravitino(createTypeBean("bool", null, null, null)));

    // Integer
    assertEquals(
        Types.LongType.get(), typeConverter.toGravitino(createTypeBean("int64", null, null, null)));

    // Float
    assertEquals(
        Types.DoubleType.get(),
        typeConverter.toGravitino(createTypeBean("float64", null, null, null)));

    // String
    assertEquals(
        Types.StringType.get(),
        typeConverter.toGravitino(createTypeBean("string", null, null, null)));

    // Binary
    assertEquals(
        Types.BinaryType.get(),
        typeConverter.toGravitino(createTypeBean("bytes", null, null, null)));

    // Date
    assertEquals(
        Types.DateType.get(), typeConverter.toGravitino(createTypeBean("date", null, null, null)));
  }

  @Test
  void testToGravitinoTimeTypes() {
    // Time without precision
    assertEquals(
        Types.TimeType.get(), typeConverter.toGravitino(createTypeBean("time", null, null, null)));

    // Time with precision
    assertEquals(
        Types.TimeType.of(6), typeConverter.toGravitino(createTypeBean("time", null, null, 6)));

    // DateTime (timestamp without timezone)
    assertEquals(
        Types.TimestampType.withoutTimeZone(),
        typeConverter.toGravitino(createTypeBean("datetime", null, null, null)));

    // DateTime with precision
    assertEquals(
        Types.TimestampType.withoutTimeZone(3),
        typeConverter.toGravitino(createTypeBean("datetime", null, null, 3)));

    // Timestamp (with timezone)
    assertEquals(
        Types.TimestampType.withTimeZone(),
        typeConverter.toGravitino(createTypeBean("timestamp", null, null, null)));

    // Timestamp with precision
    assertEquals(
        Types.TimestampType.withTimeZone(6),
        typeConverter.toGravitino(createTypeBean("timestamp", null, null, 6)));
  }

  @Test
  void testToGravitinoDecimalTypes() {
    // NUMERIC with precision and scale
    assertEquals(
        Types.DecimalType.of(10, 2),
        typeConverter.toGravitino(createTypeBean("numeric", 10, 2, null)));

    // NUMERIC with default precision and scale
    assertEquals(
        Types.DecimalType.of(38, 9),
        typeConverter.toGravitino(createTypeBean("numeric", null, null, null)));

    // BIGNUMERIC is now treated as ExternalType to avoid precision loss
    assertEquals(
        Types.ExternalType.of("BIGNUMERIC"),
        typeConverter.toGravitino(createTypeBean("bignumeric", 38, 10, null)));

    assertEquals(
        Types.ExternalType.of("BIGNUMERIC"),
        typeConverter.toGravitino(createTypeBean("bignumeric", null, null, null)));
  }

  @Test
  void testToGravitinoExternalTypes() {
    // Geography
    assertEquals(
        Types.ExternalType.of("GEOGRAPHY"),
        typeConverter.toGravitino(createTypeBean("geography", null, null, null)));

    // JSON
    assertEquals(
        Types.ExternalType.of("JSON"),
        typeConverter.toGravitino(createTypeBean("json", null, null, null)));

    // STRUCT
    assertEquals(
        Types.ExternalType.of("STRUCT"),
        typeConverter.toGravitino(createTypeBean("struct", null, null, null)));

    // RANGE
    assertEquals(
        Types.ExternalType.of("RANGE"),
        typeConverter.toGravitino(createTypeBean("range", null, null, null)));

    // Array type
    assertEquals(
        Types.ExternalType.of("ARRAY<STRING>"),
        typeConverter.toGravitino(createTypeBean("array<string>", null, null, null)));

    // Struct type with full definition
    assertEquals(
        Types.ExternalType.of("STRUCT<NAME STRING, AGE INT64>"),
        typeConverter.toGravitino(
            createTypeBean("struct<name string, age int64>", null, null, null)));

    // Range type with full definition
    assertEquals(
        Types.ExternalType.of("RANGE<DATE>"),
        typeConverter.toGravitino(createTypeBean("range<date>", null, null, null)));
  }

  @Test
  void testToGravitinoUnknownType() {
    // Unknown type should be handled as external type
    assertEquals(
        Types.ExternalType.of("UNKNOWN_TYPE"),
        typeConverter.toGravitino(createTypeBean("unknown_type", null, null, null)));
  }

  /**
   * Helper method to create a JdbcTypeBean for testing.
   *
   * @param typeName the type name
   * @param columnSize the column size (precision)
   * @param scale the scale
   * @param datetimePrecision the datetime precision
   * @return a JdbcTypeBean instance
   */
  private JdbcTypeConverter.JdbcTypeBean createTypeBean(
      String typeName, Integer columnSize, Integer scale, Integer datetimePrecision) {
    return new JdbcTypeConverter.JdbcTypeBean(typeName) {
      @Override
      public Integer getColumnSize() {
        return columnSize;
      }

      @Override
      public Integer getScale() {
        return scale;
      }

      @Override
      public Integer getDatetimePrecision() {
        return datetimePrecision;
      }
    };
  }
}
