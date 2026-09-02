/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.scim.web.rest;

import com.datastrato.gravitino.scim.web.ScimManagement;
import com.datastrato.gravitino.scim.web.ScimRESTUtils;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.utils.PrincipalUtils;

/**
 * Enforces SCIM admin API access rules.
 *
 * <p>Caller must be listed in {@link Configs#SERVICE_ADMINS}.
 */
@ScimManagement
public class ScimAuthorizationFilter implements ContainerRequestFilter {

  /** Error message when the caller is not a service admin. */
  public static final String SERVICE_ADMIN_REQUIRED_MESSAGE =
      "Only service admins can manage SCIM tokens and provisioning.";

  private final Supplier<List<String>> serviceAdminsSupplier;
  private final Supplier<String> currentUserSupplier;

  /** Creates a filter backed by the running Gravitino server configuration. */
  public ScimAuthorizationFilter() {
    this(
        () -> GravitinoEnv.getInstance().config().get(Configs.SERVICE_ADMINS),
        PrincipalUtils::getCurrentUserName);
  }

  ScimAuthorizationFilter(
      Supplier<List<String>> serviceAdminsSupplier, Supplier<String> currentUserSupplier) {
    this.serviceAdminsSupplier = serviceAdminsSupplier;
    this.currentUserSupplier = currentUserSupplier;
  }

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!isServiceAdmin(serviceAdminsSupplier.get(), currentUserSupplier.get())) {
      requestContext.abortWith(ScimRESTUtils.forbidden(SERVICE_ADMIN_REQUIRED_MESSAGE, null));
    }
  }

  static boolean isServiceAdmin(List<String> serviceAdmins, String currentUser) {
    return currentUser != null && serviceAdmins != null && serviceAdmins.contains(currentUser);
  }
}
