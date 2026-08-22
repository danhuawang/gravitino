/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastrato.gravitino.dto.responses.ExtendedTagListResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.RelationalEntity;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.dto.responses.ErrorConstants;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.TagEntity;
import org.apache.gravitino.meta.UserEntity;
import org.apache.gravitino.rest.RESTUtils;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestExtendedTagOperations extends JerseyTest {

  private static final String METALAKE = "testMetalake";

  private static final TagDispatcher tagDispatcher = mock(TagDispatcher.class);
  private static final SupportsRelationOperations ownerRelationOperations =
      mock(SupportsRelationOperations.class);

  private static class MockServletRequestFactory extends ServletRequestFactoryBase {
    @Override
    public HttpServletRequest get() {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getRemoteUser()).thenReturn(null);
      return request;
    }
  }

  @BeforeEach
  public void resetMocks() {
    reset(tagDispatcher, ownerRelationOperations);
  }

  @Override
  protected Application configure() {
    try {
      forceSet(
          TestProperties.CONTAINER_PORT, String.valueOf(RESTUtils.findAvailablePort(2000, 3000)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ResourceConfig resourceConfig = new ResourceConfig();
    resourceConfig.register(new ExtendedTagOperations(tagDispatcher, ownerRelationOperations));
    resourceConfig.register(
        new AbstractBinder() {
          @Override
          protected void configure() {
            bindFactory(MockServletRequestFactory.class).to(HttpServletRequest.class);
          }
        });
    return resourceConfig;
  }

  @Test
  public void testListTagNames() throws IOException {
    when(tagDispatcher.listTags(METALAKE)).thenReturn(new String[] {"tag1", "tag2"});

    Response response = request(false);

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    NameListResponse nameListResponse = response.readEntity(NameListResponse.class);
    Assertions.assertArrayEquals(new String[] {"tag1", "tag2"}, nameListResponse.getNames());
    verify(ownerRelationOperations, never()).batchListEntitiesByRelation(any(), any(), any());
  }

  @Test
  public void testListTagInfosWithOwners() throws IOException {
    Tag tag1 = buildTag("tag1");
    Tag tag2 = buildTag("tag2");
    when(tagDispatcher.listTagsInfo(METALAKE)).thenReturn(new Tag[] {tag1, tag2});
    UserEntity user = mock(UserEntity.class);
    when(user.nameIdentifier()).thenReturn(NameIdentifierUtil.ofUser(METALAKE, "user1"));
    when(user.type()).thenReturn(Entity.EntityType.USER);
    when(ownerRelationOperations.batchListEntitiesByRelation(
            SupportsRelationOperations.Type.OWNER_REL,
            List.of(
                NameIdentifierUtil.ofTag(METALAKE, "tag1"),
                NameIdentifierUtil.ofTag(METALAKE, "tag2")),
            Entity.EntityType.TAG))
        .thenReturn(
            List.of(
                new RelationalEntity<>(
                    SupportsRelationOperations.Type.OWNER_REL,
                    NameIdentifierUtil.ofTag(METALAKE, "tag1"),
                    Entity.EntityType.TAG,
                    user)));

    Response response = request(true);

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedTagListResponse tagListResponse = response.readEntity(ExtendedTagListResponse.class);
    Assertions.assertDoesNotThrow(tagListResponse::validate);
    Assertions.assertEquals("tag1", tagListResponse.getTags()[0].tag().name());
    Assertions.assertEquals("user1", tagListResponse.getTags()[0].owner().name());
    Assertions.assertEquals(Owner.Type.USER, tagListResponse.getTags()[0].owner().type());
    Assertions.assertEquals("tag2", tagListResponse.getTags()[1].tag().name());
    Assertions.assertNull(tagListResponse.getTags()[1].owner());
    verify(ownerRelationOperations)
        .batchListEntitiesByRelation(
            SupportsRelationOperations.Type.OWNER_REL,
            List.of(
                NameIdentifierUtil.ofTag(METALAKE, "tag1"),
                NameIdentifierUtil.ofTag(METALAKE, "tag2")),
            Entity.EntityType.TAG);
  }

  @Test
  public void testListEmptyTagInfos() throws IOException {
    when(tagDispatcher.listTagsInfo(METALAKE)).thenReturn(null);

    Response response = request(true);

    Assertions.assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    ExtendedTagListResponse tagListResponse = response.readEntity(ExtendedTagListResponse.class);
    Assertions.assertEquals(0, tagListResponse.getTags().length);
    verify(ownerRelationOperations, never()).batchListEntitiesByRelation(any(), any(), any());
  }

  @Test
  public void testListTagsNoSuchMetalake() {
    when(tagDispatcher.listTags(METALAKE))
        .thenThrow(new NoSuchMetalakeException("Metalake does not exist"));

    Response response = request(false);

    Assertions.assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
    Assertions.assertEquals(ErrorConstants.NOT_FOUND_CODE, errorResponse.getCode());
    Assertions.assertEquals(NoSuchMetalakeException.class.getSimpleName(), errorResponse.getType());
  }

  private Response request(boolean details) {
    return target("/web/metalakes/" + METALAKE + "/tags")
        .queryParam("details", details)
        .request(MediaType.APPLICATION_JSON_TYPE)
        .accept("application/vnd.gravitino.v1+json")
        .get();
  }

  private Tag buildTag(String name) {
    return TagEntity.builder()
        .withId(1L)
        .withName(name)
        .withNamespace(Namespace.of(METALAKE))
        .withComment("comment")
        .withProperties(Collections.emptyMap())
        .withAuditInfo(
            AuditInfo.builder().withCreator("creator").withCreateTime(Instant.now()).build())
        .build();
  }
}
