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
package org.apache.gravitino.integration.test.util;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for JDBC driver conflict cleanup in {@link BaseIT}. */
public class TestBaseITDriverCleanup {

  @TempDir Path tempDir;

  @Test
  void testCleanOracleDriversByExactFileName() throws Exception {
    String expectedFileName = "ojdbc11-23.26.2.0.0.jar";
    Path expectedDriver = Files.createFile(tempDir.resolve(expectedFileName));
    Path sameVersionOjdbc8 = Files.createFile(tempDir.resolve("ojdbc8-23.26.2.0.0.jar"));
    Path olderOjdbc11 = Files.createFile(tempDir.resolve("ojdbc11-23.4.0.24.05.jar"));
    Path unrelatedDriver = Files.createFile(tempDir.resolve("postgresql-42.7.11.jar"));

    BaseIT.cleanConflictingDrivers(tempDir.toString(), "oracle", expectedFileName);

    Assertions.assertTrue(Files.exists(expectedDriver));
    Assertions.assertFalse(Files.exists(sameVersionOjdbc8));
    Assertions.assertFalse(Files.exists(olderOjdbc11));
    Assertions.assertTrue(Files.exists(unrelatedDriver));
  }
}
