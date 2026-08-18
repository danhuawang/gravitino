/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization.mapper.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datastrato.gravitino.authorization.mapper.DatastratoSecurableObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TestDatastratoAuthorizationMapperPackageProvider {

  @Test
  public void testGetMapperClasses() {
    List<Class<?>> mapperClasses =
        new DatastratoAuthorizationMapperPackageProvider().getMapperClasses();

    assertEquals(List.of(DatastratoSecurableObjectMapper.class), mapperClasses);
  }
}
