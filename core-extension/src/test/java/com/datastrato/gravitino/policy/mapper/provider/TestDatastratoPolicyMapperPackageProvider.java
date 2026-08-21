/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.policy.mapper.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datastrato.gravitino.policy.mapper.DatastratoPolicyMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TestDatastratoPolicyMapperPackageProvider {

  @Test
  public void testGetMapperClasses() {
    List<Class<?>> mapperClasses = new DatastratoPolicyMapperPackageProvider().getMapperClasses();

    assertEquals(List.of(DatastratoPolicyMapper.class), mapperClasses);
  }
}
