/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.trino.connector.catalog.jdbc.oracle;

import io.trino.spi.TrinoException;
import io.trino.spi.type.DateType;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.VarcharType;
import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Type.Name;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.trino.connector.GravitinoErrorCode;
import org.apache.gravitino.trino.connector.util.GeneralDataTypeTransformer;

/** Type transformer between Oracle and Trino. */
public class OracleDataTypeTransformer extends GeneralDataTypeTransformer {

  @Override
  public io.trino.spi.type.Type getTrinoType(Type type) {
    if (type.name() == Name.EXTERNAL) {
      return VarcharType.createUnboundedVarcharType();
    }
    return super.getTrinoType(type);
  }

  @Override
  public Type getGravitinoType(io.trino.spi.type.Type type) {
    Class<? extends io.trino.spi.type.Type> typeClass = type.getClass();
    if (typeClass == VarcharType.class) {
      VarcharType varcharType = (VarcharType) type;
      // Unbounded VARCHAR must map to StringType so OracleTypeConverter produces CLOB.
      // The base class maps it to VarCharType(Integer.MAX_VALUE-1) which causes an Oracle DDL
      // error.
      return varcharType.getLength().isEmpty()
          ? Types.StringType.get()
          : Types.VarCharType.of(varcharType.getLength().get());
    } else if (typeClass == DateType.class) {
      // Oracle has no pure date type. An explicit precision of 3 makes OracleTypeConverter emit
      // "TIMESTAMP(3)" DDL, matching the millisecond precision Trino uses by default when a
      // TIMESTAMP column's precision is left unset (see GeneralDataTypeTransformer), so the value
      // reads back with the same precision it was written with.
      return Types.TimestampType.withoutTimeZone(3);
    } else if (TimeType.class.isAssignableFrom(typeClass)) {
      throw new TrinoException(
          GravitinoErrorCode.GRAVITINO_ILLEGAL_ARGUMENT, "Oracle does not support the TIME type");
    }
    return super.getGravitinoType(type);
  }
}
