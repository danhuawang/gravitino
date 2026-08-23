/*
 * Copyright 2024 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Preconditions;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/** The policy projection returned by the search API. */
@Getter
@EqualsAndHashCode(callSuper = true)
@JsonDeserialize(builder = SearchPolicyEntityDTO.Builder.class)
public class SearchPolicyEntityDTO extends SearchEntityDTO {
  @JsonProperty("policy_type")
  private final String policyType;

  @JsonProperty("enabled")
  private final boolean enabled;

  @JsonProperty("content")
  private final String content;

  private SearchPolicyEntityDTO(Builder builder) {
    super(builder);
    this.policyType = builder.policyType;
    this.enabled = builder.enabled;
    this.content = builder.content;
  }

  /** Builder for {@link SearchPolicyEntityDTO}. */
  public static class Builder extends SearchEntityDTO.Builder<Builder, SearchPolicyEntityDTO> {
    private String policyType;
    private boolean enabled;
    private String content;

    private Builder() {}

    /**
     * Creates a new builder.
     *
     * @return the builder
     */
    public static Builder builder() {
      return new Builder();
    }

    /**
     * Sets the policy type.
     *
     * @param policyType the policy type
     * @return this builder
     */
    @JsonProperty("policy_type")
    public Builder withPolicyType(String policyType) {
      this.policyType = policyType;
      return this;
    }

    /**
     * Sets whether the policy is enabled.
     *
     * @param enabled whether the policy is enabled
     * @return this builder
     */
    public Builder withEnabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /**
     * Sets the serialized policy content.
     *
     * @param content the policy content
     * @return this builder
     */
    public Builder withContent(String content) {
      this.content = content;
      return this;
    }

    @Override
    protected void validate() {
      super.validate();
      Preconditions.checkArgument(StringUtils.isNotBlank(policyType), "policyType cannot be blank");
    }

    @Override
    protected SearchPolicyEntityDTO internalBuild() {
      validate();
      return new SearchPolicyEntityDTO(this);
    }
  }
}
