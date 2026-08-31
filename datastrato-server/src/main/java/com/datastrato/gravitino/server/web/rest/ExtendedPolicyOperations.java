/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.dto.policy.ExtendedPolicyDTO;
import com.datastrato.gravitino.dto.responses.ExtendedPolicyListResponse;
import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.dto.policy.PolicyDTO;
import org.apache.gravitino.dto.responses.NameListResponse;
import org.apache.gravitino.dto.util.DTOConverters;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.authorization.annotations.AuthorizationMetadata;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.server.web.rest.ExceptionHandlers;
import org.apache.gravitino.server.web.rest.OperationType;
import org.apache.gravitino.storage.relational.service.DatastratoPolicyMetaService;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** REST operations for extended policy management under a metalake. */
@Path("web/metalakes/{metalake}/policies")
public class ExtendedPolicyOperations {

  private static final Logger LOG = LoggerFactory.getLogger(ExtendedPolicyOperations.class);

  private final PolicyDispatcher policyDispatcher;
  private final DatastratoPolicyMetaService policyMetaService;

  @Context private HttpServletRequest httpRequest;

  /** Default constructor for Jersey. */
  public ExtendedPolicyOperations() {
    this(GravitinoEnv.getInstance().policyDispatcher(), DatastratoPolicyMetaService.getInstance());
  }

  @VisibleForTesting
  ExtendedPolicyOperations(
      PolicyDispatcher policyDispatcher, DatastratoPolicyMetaService policyMetaService) {
    this.policyDispatcher = policyDispatcher;
    this.policyMetaService = policyMetaService;
  }

  /**
   * Lists policies under a metalake with details and associated objects count or names.
   *
   * @param metalake The name of the metalake.
   * @param verbose Whether to return detailed extended policy info.
   * @return The response containing extended policies or policy names.
   */
  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-extended-policies." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-extended-policies", absolute = true)
  @AuthorizationExpression(expression = "")
  public Response listPolicies(
      @PathParam("metalake") @AuthorizationMetadata(type = Entity.EntityType.METALAKE)
          String metalake,
      @QueryParam("details") @DefaultValue("false") boolean verbose) {
    LOG.info(
        "Received list extended policy {} request for metalake: {}",
        verbose ? "infos" : "names",
        metalake);

    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            if (verbose) {
              PolicyEntity[] policies = policyDispatcher.listPolicyInfos(metalake);
              policies =
                  MetadataAuthzHelper.filterByExpression(
                      metalake,
                      AuthorizationExpressionConstants.LOAD_POLICY_AUTHORIZATION_EXPRESSION,
                      Entity.EntityType.POLICY,
                      policies,
                      policy -> NameIdentifierUtil.ofPolicy(metalake, policy.name()));

              List<Long> policyIds =
                  Arrays.stream(policies).map(PolicyEntity::id).collect(Collectors.toList());
              Map<Long, List<MetadataObject>> associatedObjects =
                  policyIds.isEmpty()
                      ? Collections.emptyMap()
                      : policyMetaService.listAssociatedMetadataObjectsForPolicies(
                          metalake, policyIds);
              Map<Long, Integer> counts =
                  countVisibleAssociatedObjects(metalake, associatedObjects);

              ExtendedPolicyDTO[] policyDTOs =
                  Arrays.stream(policies)
                      .map(
                          p ->
                              ExtendedPolicyDTO.builder()
                                  .withPolicy(toDTO(p, Optional.empty()))
                                  .withAssociatedObjectsCount(counts.getOrDefault(p.id(), 0))
                                  .build())
                      .toArray(ExtendedPolicyDTO[]::new);

              LOG.info(
                  "List {} extended policies info under metalake: {}", policyDTOs.length, metalake);
              return Utils.ok(new ExtendedPolicyListResponse(policyDTOs));
            } else {
              String[] policyNames = policyDispatcher.listPolicies(metalake);
              policyNames = policyNames == null ? new String[0] : policyNames;
              policyNames =
                  MetadataAuthzHelper.filterByExpression(
                      metalake,
                      AuthorizationExpressionConstants.LOAD_POLICY_AUTHORIZATION_EXPRESSION,
                      Entity.EntityType.POLICY,
                      policyNames,
                      policyName -> NameIdentifierUtil.ofPolicy(metalake, policyName));

              LOG.info(
                  "List {} extended policies under metalake: {}", policyNames.length, metalake);
              return Utils.ok(new NameListResponse(policyNames));
            }
          });
    } catch (Exception e) {
      return ExceptionHandlers.handlePolicyException(OperationType.LIST, "", metalake, e);
    }
  }

  private static PolicyDTO toDTO(PolicyEntity policy, Optional<Boolean> inherited) {
    return PolicyDTO.builder()
        .withName(policy.name())
        .withComment(policy.comment())
        .withPolicyType(policy.policyType().policyType())
        .withEnabled(policy.enabled())
        .withContent(DTOConverters.toDTO(policy.content()))
        .withInherited(inherited)
        .withAudit(DTOConverters.toDTO(policy.auditInfo()))
        .build();
  }

  private static Map<Long, Integer> countVisibleAssociatedObjects(
      String metalake, Map<Long, List<MetadataObject>> objectsByPolicyId) {
    if (objectsByPolicyId.isEmpty()) {
      return Collections.emptyMap();
    }

    MetadataObject[] distinctObjects =
        objectsByPolicyId.values().stream()
            .flatMap(List::stream)
            .distinct()
            .toArray(MetadataObject[]::new);
    MetadataObject[] visibleObjects =
        MetadataAuthzHelper.filterMetadataObject(metalake, distinctObjects);
    Map<MetadataObject.Type, Set<String>> visibleObjectNames =
        Arrays.stream(visibleObjects)
            .collect(
                Collectors.groupingBy(
                    MetadataObject::type,
                    Collectors.mapping(MetadataObject::fullName, Collectors.toSet())));

    return objectsByPolicyId.entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                entry ->
                    Math.toIntExact(
                        entry.getValue().stream()
                            .filter(
                                object ->
                                    visibleObjectNames
                                        .getOrDefault(object.type(), Collections.emptySet())
                                        .contains(object.fullName()))
                            .count())));
  }
}
