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
package org.apache.gravitino.catalog.lakehouse.iceberg.converter;

import org.apache.gravitino.connector.DataTypeConverter;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.TypeUtil;

public class IcebergDataTypeConverter implements DataTypeConverter<Type, Type> {
  public static final IcebergDataTypeConverter CONVERTER = new IcebergDataTypeConverter();

  @Override
  public Type fromGravitino(org.apache.gravitino.rel.types.Type gravitinoType) {
    return ToIcebergTypeVisitor.visit(gravitinoType, new ToIcebergType());
  }

  @Override
  public org.apache.gravitino.rel.types.Type toGravitino(Type type) {
    return TypeUtil.visit(type, new FromIcebergType());
  }
}
