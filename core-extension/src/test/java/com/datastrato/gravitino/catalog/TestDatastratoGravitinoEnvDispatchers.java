/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.datastrato.gravitino.DatastratoGravitinoEnv;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link DatastratoGravitinoEnv} correctly overrides the internal dispatcher
 * accessors introduced in OSS, so Datastrato-aware internal dispatchers are returned instead of the
 * bare OSS ones.
 */
public class TestDatastratoGravitinoEnvDispatchers {

  @AfterEach
  public void tearDown() throws IllegalAccessException {
    FieldUtils.writeField(
        DatastratoGravitinoEnv.getInstance(), "internalDatastratoSchemaDispatcher", null, true);
    FieldUtils.writeField(
        DatastratoGravitinoEnv.getInstance(), "internalDatastratoTableDispatcher", null, true);
    FieldUtils.writeField(
        DatastratoGravitinoEnv.getInstance(), "internalDatastratoFilesetDispatcher", null, true);
  }

  @Test
  public void testInternalSchemaDispatcherOverridesOSS() throws IllegalAccessException {
    SchemaDispatcher mockInternal = mock(SchemaDispatcher.class);
    FieldUtils.writeField(
        DatastratoGravitinoEnv.getInstance(),
        "internalDatastratoSchemaDispatcher",
        mockInternal,
        true);

    // DatastratoGravitinoEnv returns its own internal dispatcher
    assertSame(mockInternal, DatastratoGravitinoEnv.getInstance().internalSchemaDispatcher());
    // GravitinoEnv (OSS) instance is separate — it cannot hold the Datastrato mock
    assertNotSame(mockInternal, GravitinoEnv.getInstance().internalSchemaDispatcher());
  }

  @Test
  public void testInternalTableDispatcherOverridesOSS() throws IllegalAccessException {
    TableDispatcher mockInternal = mock(TableDispatcher.class);
    FieldUtils.writeField(
        DatastratoGravitinoEnv.getInstance(),
        "internalDatastratoTableDispatcher",
        mockInternal,
        true);

    assertSame(mockInternal, DatastratoGravitinoEnv.getInstance().internalTableDispatcher());
    // GravitinoEnv.internalTableDispatcher() is null in unit-test context — not the same object
    assertNotSame(mockInternal, GravitinoEnv.getInstance().internalTableDispatcher());
  }

  @Test
  public void testInternalFilesetDispatcherOverridesOSS() throws IllegalAccessException {
    FilesetDispatcher mockInternal = mock(FilesetDispatcher.class);
    FieldUtils.writeField(
        DatastratoGravitinoEnv.getInstance(),
        "internalDatastratoFilesetDispatcher",
        mockInternal,
        true);

    assertSame(mockInternal, DatastratoGravitinoEnv.getInstance().internalFilesetDispatcher());
    // GravitinoEnv.internalFilesetDispatcher() throws when null — read field directly to compare
    assertNotSame(
        mockInternal,
        FieldUtils.readField(GravitinoEnv.getInstance(), "internalFilesetDispatcher", true));
  }

  @Test
  public void testInternalSchemaDiffersFromPublicSchema() throws IllegalAccessException {
    // datastratoSchemaDispatcher field is typed DatastratoSchemaDispatcher
    DatastratoSchemaDispatcher mockPublicSchema = mock(DatastratoSchemaDispatcher.class);
    SchemaDispatcher mockInternalSchema = mock(SchemaDispatcher.class);

    Object originalPublic =
        FieldUtils.readField(
            DatastratoGravitinoEnv.getInstance(), "datastratoSchemaDispatcher", true);
    try {
      FieldUtils.writeField(
          DatastratoGravitinoEnv.getInstance(),
          "datastratoSchemaDispatcher",
          mockPublicSchema,
          true);
      FieldUtils.writeField(
          DatastratoGravitinoEnv.getInstance(),
          "internalDatastratoSchemaDispatcher",
          mockInternalSchema,
          true);

      SchemaDispatcher publicDispatcher = DatastratoGravitinoEnv.getInstance().schemaDispatcher();
      SchemaDispatcher internalDispatcher =
          DatastratoGravitinoEnv.getInstance().internalSchemaDispatcher();

      assertNotNull(publicDispatcher);
      assertNotNull(internalDispatcher);
      assertNotSame(
          publicDispatcher,
          internalDispatcher,
          "Internal schema dispatcher must be a distinct instance from the public dispatcher");
    } finally {
      FieldUtils.writeField(
          DatastratoGravitinoEnv.getInstance(), "datastratoSchemaDispatcher", originalPublic, true);
    }
  }

  @Test
  public void testInternalTableDiffersFromPublicTable() throws IllegalAccessException {
    // datastratoTableDispatcher field is typed DatastratoTableDispatcher
    DatastratoTableDispatcher mockPublicTable = mock(DatastratoTableDispatcher.class);
    TableDispatcher mockInternalTable = mock(TableDispatcher.class);

    Object originalPublic =
        FieldUtils.readField(
            DatastratoGravitinoEnv.getInstance(), "datastratoTableDispatcher", true);
    try {
      FieldUtils.writeField(
          DatastratoGravitinoEnv.getInstance(), "datastratoTableDispatcher", mockPublicTable, true);
      FieldUtils.writeField(
          DatastratoGravitinoEnv.getInstance(),
          "internalDatastratoTableDispatcher",
          mockInternalTable,
          true);

      assertNotSame(
          DatastratoGravitinoEnv.getInstance().tableDispatcher(),
          DatastratoGravitinoEnv.getInstance().internalTableDispatcher(),
          "Internal table dispatcher must be a distinct instance from the public dispatcher");
    } finally {
      FieldUtils.writeField(
          DatastratoGravitinoEnv.getInstance(), "datastratoTableDispatcher", originalPublic, true);
    }
  }

  @Test
  public void testInternalFilesetDiffersFromPublicFileset() throws IllegalAccessException {
    // datastratoFilesetDispatcher field is typed DatastratoFilesetDispatcher
    DatastratoFilesetDispatcher mockPublicFileset = mock(DatastratoFilesetDispatcher.class);
    FilesetDispatcher mockInternalFileset = mock(FilesetDispatcher.class);

    Object originalPublic =
        FieldUtils.readField(
            DatastratoGravitinoEnv.getInstance(), "datastratoFilesetDispatcher", true);
    try {
      FieldUtils.writeField(
          DatastratoGravitinoEnv.getInstance(),
          "datastratoFilesetDispatcher",
          mockPublicFileset,
          true);
      FieldUtils.writeField(
          DatastratoGravitinoEnv.getInstance(),
          "internalDatastratoFilesetDispatcher",
          mockInternalFileset,
          true);

      assertNotSame(
          DatastratoGravitinoEnv.getInstance().filesetDispatcher(),
          DatastratoGravitinoEnv.getInstance().internalFilesetDispatcher(),
          "Internal fileset dispatcher must be a distinct instance from the public dispatcher");
    } finally {
      FieldUtils.writeField(
          DatastratoGravitinoEnv.getInstance(),
          "datastratoFilesetDispatcher",
          originalPublic,
          true);
    }
  }
}
