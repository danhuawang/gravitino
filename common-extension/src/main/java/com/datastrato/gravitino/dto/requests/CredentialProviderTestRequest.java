/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.dto.requests.CatalogUpdateRequest;
import org.apache.gravitino.rest.RESTRequest;

/** Represents a request to test a credential provider. */
@Getter
@EqualsAndHashCode
public class CredentialProviderTestRequest implements RESTRequest {

  @JsonProperty("path")
  private final String path;

  @JsonProperty("updates")
  private final List<CatalogUpdateRequest> updates;

  /**
   * Creates a credential provider test request.
   *
   * @param path The storage path used to generate a scoped credential.
   * @param updates The proposed Catalog updates to apply for this test. {@code null} is treated as
   *     an empty list.
   */
  @JsonCreator
  public CredentialProviderTestRequest(
      @JsonProperty("path") String path,
      @JsonProperty("updates") @Nullable List<CatalogUpdateRequest> updates) {
    this.path = path;
    this.updates = updates == null ? Collections.emptyList() : updates;
  }

  /**
   * Creates a credential provider test request using the persisted Catalog configuration.
   *
   * @param path The storage path used to generate a scoped credential.
   */
  public CredentialProviderTestRequest(String path) {
    this(path, Collections.emptyList());
  }

  /** Validates the request. */
  @Override
  public void validate() throws IllegalArgumentException {
    Preconditions.checkArgument(
        StringUtils.isNotBlank(path), "\"path\" field is required and cannot be empty");
    updates.forEach(
        update -> {
          update.validate();
          Preconditions.checkArgument(
              !(update instanceof CatalogUpdateRequest.SetCatalogSecretReferenceRequest),
              "setSecretReference updates are not supported by credential provider tests");
        });
  }
}
