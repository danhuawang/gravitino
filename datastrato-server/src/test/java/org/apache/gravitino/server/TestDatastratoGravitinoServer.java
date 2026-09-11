/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.apache.gravitino.GravitinoEnv;
import org.junit.jupiter.api.Test;

class TestDatastratoGravitinoServer {

  @Test
  void testConstructorDoesNotAccessUninitializedEnvironmentComponents() {
    GravitinoEnv gravitinoEnv = mock(GravitinoEnv.class);

    assertDoesNotThrow(() -> new DatastratoGravitinoServer(new ServerConfig(false), gravitinoEnv));

    verify(gravitinoEnv, never()).catalogManager();
  }
}
