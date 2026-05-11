/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.dto;

import com.datastrato.gravitino.license.LicenseManager;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.time.LocalDate;
import lombok.Data;

@Data
public class LicenseStatusDTO {

  @JsonProperty("issuedTo")
  private String issuedTo;

  @JsonProperty("licenseId")
  private String licenseId;

  @JsonProperty("expiresAt")
  private String expiresAt;

  @JsonProperty("daysRemaining")
  private long daysRemaining;

  @JsonProperty("gracePeriodDays")
  private int gracePeriodDays;

  @JsonProperty("status")
  private String status;

  @JsonProperty("maxNodes")
  private int maxNodes;

  @JsonProperty("activeNodes")
  private int activeNodes;

  public static LicenseStatusDTO from(LicenseManager.LicenseStatus s) {
    Preconditions.checkArgument(s != null, "License status is not yet available");

    LicenseStatusDTO dto = new LicenseStatusDTO();
    dto.issuedTo = s.getIssuedTo();
    dto.licenseId = s.getLicenseId();
    dto.expiresAt = LocalDate.ofEpochDay(s.getExpiresAtDays()) + "T00:00:00Z";
    dto.daysRemaining = s.getDaysRemaining();
    dto.gracePeriodDays = s.getGracePeriodDays();
    dto.status = s.getStatusCode().name();
    dto.maxNodes = s.getMaxNodes();
    dto.activeNodes = s.getActiveNodes();
    return dto;
  }
}
