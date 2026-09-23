/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Set;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.Privilege;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.junit.jupiter.api.Test;

class TestMetricsAuthorizationFilter {
  private static final String METALAKE = "metalake";

  @Test
  void testReusesOneSynchronousFilterAndFailsClosedPerEntity() {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    when(authorizer.authorize(any(), eq(METALAKE), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              MetadataObject object = invocation.getArgument(2);
              Privilege.Name privilege = invocation.getArgument(3);
              return object.type() == MetadataObject.Type.TABLE
                  && object.name().equals("visible")
                  && privilege == Privilege.Name.SELECT_TABLE;
            });
    when(authorizer.deny(any(), eq(METALAKE), any(), any(), any())).thenReturn(false);
    when(authorizer.isOwner(any(), eq(METALAKE), any(), any())).thenReturn(false);

    Set<String> visible =
        MetricsAuthorizationFilter.filterVisibleEntities(
            METALAKE,
            AuthorizationExpressionConstants.FILTER_TABLE_AUTHORIZATION_EXPRESSION,
            Entity.EntityType.TABLE,
            Arrays.asList("visible", "hidden", "broken"),
            table -> {
              if (table.equals("broken")) {
                throw new IllegalArgumentException("invalid test identifier");
              }
              return NameIdentifierUtil.ofTable(METALAKE, "catalog", "schema", table);
            },
            new UserPrincipal("user"),
            authorizer);

    assertEquals(Set.of("visible"), visible);
  }
}
