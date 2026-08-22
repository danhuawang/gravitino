/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.server.web.rest;

import com.datastrato.gravitino.dto.responses.ExtendedTagListResponse;
import com.datastrato.gravitino.dto.tag.ExtendedTagDTO;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.RelationalEntity;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.authorization.Owner;
import org.apache.gravitino.dto.authorization.OwnerDTO;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.dto.tag.TagDTO;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.tag.Tag;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** REST operations for listing tags with enterprise-only information. */
@Path("web/metalakes/{metalake}/tags")
public class ExtendedTagOperations {

  private static final Logger LOG = LoggerFactory.getLogger(ExtendedTagOperations.class);

  private final TagDispatcher tagDispatcher;
  @Nullable private final SupportsRelationOperations ownerRelationOperations;

  @Context private HttpServletRequest httpRequest;

  /** Default constructor for Jersey. */
  public ExtendedTagOperations() {
    this(
        GravitinoEnv.getInstance().tagDispatcher(),
        GravitinoEnv.getInstance().ownerDispatcher() == null
            ? null
            : GravitinoEnv.getInstance().entityStore().relationOperations());
  }

  @VisibleForTesting
  ExtendedTagOperations(
      TagDispatcher tagDispatcher, @Nullable SupportsRelationOperations ownerRelationOperations) {
    this.tagDispatcher = tagDispatcher;
    this.ownerRelationOperations = ownerRelationOperations;
  }

  /**
   * Lists tag names or detailed tag information with owners under a metalake.
   *
   * @param metalake The metalake name.
   * @param verbose Whether to return detailed tag information and owners.
   * @return The response containing tag names or detailed tags with owners.
   */
  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @AuthorizationExpression(expression = "")
  public Response listTags(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @QueryParam("details") @DefaultValue("false") boolean verbose) {
    LOG.info(
        "Received list extended tag {} request for metalake: {}",
        verbose ? "infos" : "names",
        metalake);

    try {
      return Utils.doAs(
          httpRequest, () -> verbose ? listTagInfos(metalake) : listTagNames(metalake));
    } catch (Exception e) {
      return ExceptionHandlers.handleTagException(OperationType.LIST, "", metalake, e);
    }
  }

  private Response listTagInfos(String metalake) throws IOException {
    Tag[] tags = tagDispatcher.listTagsInfo(metalake);
    tags = tags == null ? new Tag[0] : tags;
    tags =
        MetadataAuthzHelper.filterByExpression(
            metalake,
            AuthorizationExpressionConstants.LOAD_TAG_AUTHORIZATION_EXPRESSION,
            Entity.EntityType.TAG,
            tags,
            tag -> NameIdentifierUtil.ofTag(metalake, tag.name()));

    Map<NameIdentifier, OwnerDTO> owners = batchGetOwners(metalake, tags);
    ExtendedTagDTO[] tagDTOs =
        Arrays.stream(tags)
            .map(
                tag ->
                    toExtendedTagDTO(
                        tag, owners.get(NameIdentifierUtil.ofTag(metalake, tag.name()))))
            .toArray(ExtendedTagDTO[]::new);
    LOG.info("List {} extended tags info under metalake: {}", tagDTOs.length, metalake);
    return Utils.ok(new ExtendedTagListResponse(tagDTOs));
  }

  private Response listTagNames(String metalake) {
    String[] tagNames = tagDispatcher.listTags(metalake);
    tagNames = tagNames == null ? new String[0] : tagNames;
    tagNames =
        MetadataAuthzHelper.filterByExpression(
            metalake,
            AuthorizationExpressionConstants.LOAD_TAG_AUTHORIZATION_EXPRESSION,
            Entity.EntityType.TAG,
            tagNames,
            tagName -> NameIdentifierUtil.ofTag(metalake, tagName));
    LOG.info("List {} extended tags under metalake: {}", tagNames.length, metalake);
    return Utils.ok(new NameListResponse(tagNames));
  }

  private ExtendedTagDTO toExtendedTagDTO(Tag tag, @Nullable OwnerDTO ownerDTO) {
    TagDTO tagDTO = DTOConverters.toDTO(tag, Optional.empty());
    return ExtendedTagDTO.builder().withTag(tagDTO).withOwner(ownerDTO).build();
  }

  private Map<NameIdentifier, OwnerDTO> batchGetOwners(String metalake, Tag[] tags)
      throws IOException {
    if (ownerRelationOperations == null || tags.length == 0) {
      return Collections.emptyMap();
    }

    List<NameIdentifier> tagIdentifiers =
        Arrays.stream(tags)
            .map(tag -> NameIdentifierUtil.ofTag(metalake, tag.name()))
            .collect(Collectors.toList());
    return ownerRelationOperations
        .batchListEntitiesByRelation(
            SupportsRelationOperations.Type.OWNER_REL, tagIdentifiers, Entity.EntityType.TAG)
        .stream()
        .collect(Collectors.toMap(RelationalEntity::source, this::toOwnerDTO));
  }

  private OwnerDTO toOwnerDTO(RelationalEntity<?> ownerRelation) {
    Owner.Type ownerType;
    switch (ownerRelation.targetEntity().type()) {
      case USER:
        ownerType = Owner.Type.USER;
        break;
      case GROUP:
        ownerType = Owner.Type.GROUP;
        break;
      default:
        throw new IllegalArgumentException(
            "Unsupported owner entity type: " + ownerRelation.targetEntity().type());
    }

    return OwnerDTO.builder()
        .withName(ownerRelation.targetEntity().nameIdentifier().name())
        .withType(ownerType)
        .build();
  }
}
