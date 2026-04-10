/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.oracle.converter;

import org.apache.gravitino.catalog.jdbc.converter.JdbcTypeConverter;
import org.apache.gravitino.rel.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOracleTypeConverter {

  private static final String UNSUPPORTED_MSG = "To be implemented in the future";
  private static final OracleTypeConverter CONVERTER = new OracleTypeConverter();

  @Test
  void testUnsupportedConversionMethods() {
    UnsupportedOperationException toGravitinoException =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () ->
                CONVERTER.toGravitino(
                    new JdbcTypeConverter.JdbcTypeBean(OracleTypeConverter.NUMBER)));
    Assertions.assertTrue(toGravitinoException.getMessage().contains(UNSUPPORTED_MSG));

    UnsupportedOperationException fromGravitinoException =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> CONVERTER.fromGravitino(Types.IntegerType.get()));
    Assertions.assertTrue(fromGravitinoException.getMessage().contains(UNSUPPORTED_MSG));
  }

  @Test
  void testOracleTypeConstants() {
    Assertions.assertEquals("NUMBER", OracleTypeConverter.NUMBER);
    Assertions.assertEquals("VARCHAR2", OracleTypeConverter.VARCHAR2);
    Assertions.assertEquals("CHAR", OracleTypeConverter.CHAR);
    Assertions.assertEquals("NVARCHAR2", OracleTypeConverter.NVARCHAR2);
    Assertions.assertEquals("NCHAR", OracleTypeConverter.NCHAR);
    Assertions.assertEquals("CLOB", OracleTypeConverter.CLOB);
    Assertions.assertEquals("NCLOB", OracleTypeConverter.NCLOB);
    Assertions.assertEquals("BLOB", OracleTypeConverter.BLOB);
    Assertions.assertEquals("FLOAT", OracleTypeConverter.FLOAT);
    Assertions.assertEquals("BINARY_FLOAT", OracleTypeConverter.BINARY_FLOAT);
    Assertions.assertEquals("BINARY_DOUBLE", OracleTypeConverter.BINARY_DOUBLE);
    Assertions.assertEquals("RAW", OracleTypeConverter.RAW);
    Assertions.assertEquals("LONG RAW", OracleTypeConverter.LONG_RAW);
    Assertions.assertEquals("TIMESTAMP", OracleTypeConverter.TIMESTAMP);
    Assertions.assertEquals("DATE", OracleTypeConverter.DATE);
  }
}
