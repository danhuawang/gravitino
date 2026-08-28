/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization.mapper.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datastrato.gravitino.authorization.mapper.DatastratoGroupMetaMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoRoleAssignmentMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoSecurableObjectMapper;
import com.datastrato.gravitino.authorization.mapper.DatastratoUserMetaMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests mapper registration for enterprise authorization queries. */
public class TestDatastratoAuthorizationMapperPackageProvider {

  /** Verifies that all enterprise authorization mappers are registered. */
  @Test
  public void testGetMapperClasses() {
    List<Class<?>> mapperClasses =
        new DatastratoAuthorizationMapperPackageProvider().getMapperClasses();

    assertEquals(
        List.of(
            DatastratoRoleAssignmentMapper.class,
            DatastratoSecurableObjectMapper.class,
            DatastratoUserMetaMapper.class,
            DatastratoGroupMetaMapper.class),
        mapperClasses);
  }
}
