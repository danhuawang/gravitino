package org.apache.gravitino.catalog.bigquery.converter;

import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.expressions.Expression;
import org.apache.gravitino.rel.expressions.UnparsedExpression;
import org.apache.gravitino.rel.expressions.literals.Literals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BigQueryColumnDefaultValueConverter}. */
public class TestBigQueryColumnDefaultValueConverter {

  private BigQueryColumnDefaultValueConverter converter;

  @BeforeEach
  void setUp() {
    converter = new BigQueryColumnDefaultValueConverter();
  }

  @Test
  void testNullValues() {
    JdbcTypeConverter.JdbcTypeBean typeBean = createTypeBean("string", null, null, null);

    // Null value with nullable column
    Expression result = converter.toGravitino(typeBean, null, false, true);
    assertEquals(Literals.NULL, result);

    // Null value with non-nullable column
    result = converter.toGravitino(typeBean, null, false, false);
    assertEquals(DEFAULT_VALUE_NOT_SET, result);

    // Explicit NULL string
    result = converter.toGravitino(typeBean, "NULL", false, true);
    assertEquals(Literals.NULL, result);
  }

  @Test
  void testStringValues() {
    JdbcTypeConverter.JdbcTypeBean typeBean = createTypeBean("string", null, null, null);

    // Quoted string
    Expression result = converter.toGravitino(typeBean, "'hello world'", false, true);
    assertEquals(Literals.stringLiteral("hello world"), result);

    // Unquoted string (treated as string literal for STRING type)
    result = converter.toGravitino(typeBean, "hello", false, true);
    assertEquals(Literals.stringLiteral("hello"), result);
  }

  @Test
  void testBooleanValues() {
    JdbcTypeConverter.JdbcTypeBean typeBean = createTypeBean("bool", null, null, null);

    // Boolean true
    Expression result = converter.toGravitino(typeBean, "true", false, true);
    assertEquals(Literals.booleanLiteral(true), result);

    // Boolean false
    result = converter.toGravitino(typeBean, "false", false, true);
    assertEquals(Literals.booleanLiteral(false), result);

    // Case insensitive
    result = converter.toGravitino(typeBean, "TRUE", false, true);
    assertEquals(Literals.booleanLiteral(true), result);

    result = converter.toGravitino(typeBean, "FALSE", false, true);
    assertEquals(Literals.booleanLiteral(false), result);
  }

  @Test
  void testNumericValues() {
    // INT64 type
    JdbcTypeConverter.JdbcTypeBean int64TypeBean = createTypeBean("int64", null, null, null);
    Expression result = converter.toGravitino(int64TypeBean, "123", false, true);
    assertEquals(Literals.longLiteral(123L), result);

    // FLOAT64 type
    JdbcTypeConverter.JdbcTypeBean float64TypeBean = createTypeBean("float64", null, null, null);
    result = converter.toGravitino(float64TypeBean, "123.45", false, true);
    assertEquals(Literals.doubleLiteral(123.45), result);

    // Invalid numeric value should return unparsed expression
    result = converter.toGravitino(int64TypeBean, "not_a_number", false, true);
    assertTrue(result instanceof UnparsedExpression);
    assertEquals("not_a_number", ((UnparsedExpression) result).unparsedExpression());
  }

  @Test
  void testDecimalValues() {
    // NUMERIC type with precision and scale
    JdbcTypeConverter.JdbcTypeBean numericTypeBean = createTypeBean("numeric", 10, 2, null);
    Expression result = converter.toGravitino(numericTypeBean, "123.45", false, true);
    assertTrue(result instanceof Literals.LiteralImpl);

    // BIGNUMERIC type - now treated as external type, returns unparsed expression
    JdbcTypeConverter.JdbcTypeBean bigNumericTypeBean = createTypeBean("bignumeric", 20, 5, null);
    result = converter.toGravitino(bigNumericTypeBean, "12345.67890", false, true);
    assertTrue(result instanceof UnparsedExpression);
    assertEquals("12345.67890", ((UnparsedExpression) result).unparsedExpression());

    // Invalid decimal should return unparsed expression
    result = converter.toGravitino(numericTypeBean, "invalid_decimal", false, true);
    assertTrue(result instanceof UnparsedExpression);
  }

  @Test
  void testExpressionValues() {
    JdbcTypeConverter.JdbcTypeBean typeBean = createTypeBean("timestamp", null, null, null);

    // Expression values should return unparsed expression
    Expression result = converter.toGravitino(typeBean, "CURRENT_TIMESTAMP()", true, true);
    assertTrue(result instanceof UnparsedExpression);
    assertEquals("CURRENT_TIMESTAMP()", ((UnparsedExpression) result).unparsedExpression());
  }

  @Test
  void testUnknownTypes() {
    JdbcTypeConverter.JdbcTypeBean typeBean = createTypeBean("unknown_type", null, null, null);

    // Unknown types should return unparsed expression
    Expression result = converter.toGravitino(typeBean, "some_value", false, true);
    assertTrue(result instanceof UnparsedExpression);
    assertEquals("some_value", ((UnparsedExpression) result).unparsedExpression());
  }

  @Test
  void testQuotedStrings() {
    JdbcTypeConverter.JdbcTypeBean typeBean = createTypeBean("string", null, null, null);

    // Test various quoted string formats
    Expression result = converter.toGravitino(typeBean, "'simple string'", false, true);
    assertEquals(Literals.stringLiteral("simple string"), result);

    // Empty quoted string
    result = converter.toGravitino(typeBean, "''", false, true);
    assertEquals(Literals.stringLiteral(""), result);

    // String with spaces
    result = converter.toGravitino(typeBean, "'  spaced  '", false, true);
    assertEquals(Literals.stringLiteral("  spaced  "), result);
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
