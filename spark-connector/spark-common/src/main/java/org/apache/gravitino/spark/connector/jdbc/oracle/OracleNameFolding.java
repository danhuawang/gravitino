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
package org.apache.gravitino.spark.connector.jdbc.oracle;

import com.google.common.base.Preconditions;
import java.util.Locale;

/**
 * Folds a Gravitino Oracle logical name to its physical Oracle form, mirroring the rule implemented
 * by {@code OracleIdentifierUtil} in the {@code catalog-jdbc-oracle} module (a module the
 * spark-connector cannot depend on): a quoted logical name (e.g. {@code "MyTable"}) preserves its
 * exact case; an unquoted logical name is folded to uppercase, matching Oracle's own
 * unquoted-identifier folding rule.
 */
final class OracleNameFolding {

  private static final char QUOTE = '"';

  private OracleNameFolding() {}

  private static boolean isQuoted(String name) {
    return name.length() >= 2 && name.charAt(0) == QUOTE && name.charAt(name.length() - 1) == QUOTE;
  }

  /**
   * Returns the physical Oracle name for a Gravitino logical name: a quoted logical name is
   * unquoted with its case preserved; an unquoted logical name is uppercased.
   *
   * <p>Oracle does not support an embedded {@code "} inside a quoted identifier at all — unlike
   * ANSI SQL/PostgreSQL, there is no {@code ""}-doubling escape (Oracle rejects it with {@code
   * ORA-25716: The identifier contains a double quotation mark}) — so any {@code "} found inside
   * the quotes, doubled or not, means the input cannot address a real Oracle object.
   *
   * @throws IllegalArgumentException if {@code name} is quoted and its content contains an embedded
   *     {@code "}.
   */
  static String toPhysicalName(String name) {
    if (isQuoted(name)) {
      String inner = name.substring(1, name.length() - 1);
      Preconditions.checkArgument(
          inner.indexOf(QUOTE) < 0,
          "Oracle does not support an embedded '\"' in a quoted identifier: %s",
          name);
      return inner;
    }
    return name.toUpperCase(Locale.ROOT);
  }
}
