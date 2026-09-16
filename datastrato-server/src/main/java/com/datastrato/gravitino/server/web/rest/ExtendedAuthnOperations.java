/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.server.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.ExtendedDatastratoGravitinoEnv;
import com.datastrato.gravitino.authorization.DatastratoAccessControlDispatcher;
import com.datastrato.gravitino.dto.authorization.AuthMeRoleDTO;
import com.datastrato.gravitino.dto.authorization.IdentitySource;
import com.datastrato.gravitino.dto.responses.ExtendedAuthMeResponse;
import com.datastrato.gravitino.server.web.InternalApiPaths;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.server.authorization.NameBindings;
import org.apache.gravitino.server.authorization.annotations.AuthorizationExpression;
import org.apache.gravitino.server.web.Utils;
import org.apache.gravitino.utils.PrincipalUtils;

/**
 * Enterprise authenticated-user endpoint, analogous to OSS {@code GET /authn/me}.
 *
 * <p>Returns the same principal / serviceAdmin fields plus every identity-store {@code origin} for
 * the user, metalakes owned by the user, and lightweight direct roles (name + securable objects
 * with ALLOW privilege names) keyed by metalake.
 */
@NameBindings.AccessControlInterfaces
@Path(InternalApiPaths.PREFIX + "/security/authn")
public class ExtendedAuthnOperations {

  private final DatastratoAccessControlDispatcher accessControlDispatcher;

  @Context private HttpServletRequest httpRequest;

  /** Creates the resource using the enterprise access-control dispatcher. */
  public ExtendedAuthnOperations() {
    this.accessControlDispatcher =
        ExtendedDatastratoGravitinoEnv.getInstance().accessControlDispatcher();
  }

  ExtendedAuthnOperations(DatastratoAccessControlDispatcher accessControlDispatcher) {
    this.accessControlDispatcher = accessControlDispatcher;
  }

  /**
   * Returns authenticated principal information for the current user.
   *
   * @return Principal, serviceAdmin, origins, and roles-by-metalake.
   */
  @GET
  @Path("me")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "web-authn-me." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "web-authn-me", absolute = true)
  @AuthorizationExpression(expression = "")
  public Response me() {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            String principal = PrincipalUtils.getCurrentUserName();
            boolean serviceAdmin = accessControlDispatcher.isServiceAdmin(principal);
            List<IdentitySource> origins = accessControlDispatcher.listUserOrigins(principal);
            List<String> ownedMetalakes = accessControlDispatcher.listOwnedMetalakes(principal);
            Map<String, List<AuthMeRoleDTO>> roles =
                accessControlDispatcher.listUserRolesByMetalake(principal);
            return Utils.ok(
                new ExtendedAuthMeResponse(
                    principal, serviceAdmin, origins, ownedMetalakes, roles));
          });
    } catch (Exception e) {
      return Utils.internalError(e.getMessage(), e);
    }
  }
}
