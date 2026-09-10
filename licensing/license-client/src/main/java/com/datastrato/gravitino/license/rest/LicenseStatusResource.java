/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.license.LicenseManager;
import com.datastrato.gravitino.license.dto.LicenseStatusDTO;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.gravitino.metrics.MetricNames;
import org.apache.gravitino.server.web.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/license")
@Produces(MediaType.APPLICATION_JSON)
public class LicenseStatusResource {

  private static final Logger LOG = LoggerFactory.getLogger(LicenseStatusResource.class);

  private final LicenseManager licenseManager;

  public LicenseStatusResource() {
    this(LicenseManager.getInstance());
  }

  LicenseStatusResource(LicenseManager licenseManager) {
    this.licenseManager = licenseManager;
  }

  @GET
  @Path("/status")
  @Timed(name = "get-license-status." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "get-license-status", absolute = true)
  public Response getStatus() {
    try {
      return Response.ok(LicenseStatusDTO.from(licenseManager.getStatus())).build();
    } catch (Exception e) {
      LOG.error("Failed to retrieve license status", e);
      return Utils.internalError("Failed to retrieve license status", e);
    }
  }
}
