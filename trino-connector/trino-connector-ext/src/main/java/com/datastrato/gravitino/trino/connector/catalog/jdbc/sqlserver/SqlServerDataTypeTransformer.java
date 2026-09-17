/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.trino.connector.catalog.jdbc.sqlserver;

import io.trino.spi.TrinoException;
import io.trino.spi.type.CharType;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Type.Name;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.trino.connector.GravitinoErrorCode;
import org.apache.gravitino.trino.connector.util.GeneralDataTypeTransformer;

/** Type transformer between SQL Server and Trino */
public class SqlServerDataTypeTransformer extends GeneralDataTypeTransformer {

  @Override
  public io.trino.spi.type.Type getTrinoType(Type type) {
    if (Name.TIMESTAMP == type.name()) {
      Types.TimestampType timestampType = (Types.TimestampType) type;
      if (timestampType.hasTimeZone()) {
        throw new TrinoException(
            GravitinoErrorCode.GRAVITINO_UNSUPPORTED_GRAVITINO_DATATYPE,
            "SQL Server does not support TIMESTAMP WITH TIME ZONE");
      }
      return super.getTrinoType(type);
    } else if (Name.FIXED == type.name()) {
      return VarbinaryType.VARBINARY;
    } else if (Name.EXTERNAL == type.name()) {
      return VarcharType.createUnboundedVarcharType();
    }

    return super.getTrinoType(type);
  }

  @Override
  public Type getGravitinoType(io.trino.spi.type.Type type) {
    Class<? extends io.trino.spi.type.Type> typeClass = type.getClass();
    if (typeClass == CharType.class) {
      CharType charType = (CharType) type;

      if (charType.getLength() == 0) {
        throw new TrinoException(
            GravitinoErrorCode.GRAVITINO_ILLEGAL_ARGUMENT,
            "SQL Server does not support the datatype CHAR with the length 0");
      }

      return Types.FixedCharType.of(charType.getLength());
    } else if (typeClass == VarcharType.class) {
      VarcharType varcharType = (VarcharType) type;

      if (varcharType.getLength().isEmpty()) {
        return Types.StringType.get();
      }

      return Types.VarCharType.of(varcharType.getLength().get());
    }

    return super.getGravitinoType(type);
  }
}
