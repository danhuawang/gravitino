/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.dto.AuditDTO;

/** Whitelisted Catalog information required by the Connect overview page. */
@Getter
@ToString
@EqualsAndHashCode
public class ConnectionOverviewDTO {
  @JsonProperty("name")
  private final String name;

  @JsonProperty("type")
  private final Catalog.Type type;

  @JsonProperty("provider")
  private final String provider;

  @Nullable
  @JsonProperty("comment")
  private final String comment;

  @Nullable
  @JsonProperty("cloudName")
  private final String cloudName;

  @Nullable
  @JsonProperty("cloudRegionCode")
  private final String cloudRegionCode;

  @JsonProperty("audit")
  private final AuditDTO audit;

  @JsonProperty("endpoint")
  private final String endpoint;

  @JsonProperty("testStatus")
  private final ConnectionTestStatusDTO testStatus;

  @JsonProperty("credentialProviders")
  private final CredentialProviderStatusDTO[] credentialProviders;

  /**
   * Creates a connection overview.
   *
   * @param name The Catalog name.
   * @param type The Catalog type.
   * @param provider The Catalog provider.
   * @param comment The Catalog comment.
   * @param cloudName The configured cloud name, or {@code null} when absent.
   * @param cloudRegionCode The configured cloud region code, or {@code null} when absent.
   * @param audit The Catalog audit information.
   * @param endpoint The safe Connect display endpoint.
   * @param testStatus The latest valid manual connection test status.
   * @param credentialProviders The configured credential providers and their latest test statuses.
   */
  public ConnectionOverviewDTO(
      String name,
      Catalog.Type type,
      String provider,
      @Nullable String comment,
      @Nullable String cloudName,
      @Nullable String cloudRegionCode,
      AuditDTO audit,
      String endpoint,
      ConnectionTestStatusDTO testStatus,
      CredentialProviderStatusDTO[] credentialProviders) {
    this.name = name;
    this.type = type;
    this.provider = provider;
    this.comment = comment;
    this.cloudName = cloudName;
    this.cloudRegionCode = cloudRegionCode;
    this.audit = audit;
    this.endpoint = endpoint;
    this.testStatus = testStatus;
    this.credentialProviders = credentialProviders;
  }

  /** Creates an empty instance for Jackson deserialization. */
  public ConnectionOverviewDTO() {
    this(null, null, null, null, null, null, null, null, null, null);
  }

  /**
   * Validates the overview fields.
   *
   * @throws IllegalArgumentException If a required field is missing or invalid.
   */
  public void validate() {
    Preconditions.checkArgument(StringUtils.isNotBlank(name), "name cannot be blank");
    Preconditions.checkArgument(type != null, "type cannot be null");
    Preconditions.checkArgument(StringUtils.isNotBlank(provider), "provider cannot be blank");
    Preconditions.checkArgument(audit != null, "audit cannot be null");
    Preconditions.checkArgument(StringUtils.isNotBlank(endpoint), "endpoint cannot be blank");
    Preconditions.checkArgument(testStatus != null, "testStatus cannot be null");
    testStatus.validate();
    Preconditions.checkArgument(credentialProviders != null, "credentialProviders cannot be null");
    for (CredentialProviderStatusDTO credentialProvider : credentialProviders) {
      Preconditions.checkArgument(
          credentialProvider != null, "credentialProviders cannot contain null");
      credentialProvider.validate();
    }
  }
}
