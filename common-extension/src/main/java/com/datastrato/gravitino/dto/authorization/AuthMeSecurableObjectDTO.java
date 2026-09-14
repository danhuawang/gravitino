/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.dto.authorization;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.MetadataObject;

/**
 * Lightweight securable object for enterprise {@code /authn/me}: object full name, type, and
 * distinct ALLOW privilege names.
 */
@Getter
@ToString
@EqualsAndHashCode
public class AuthMeSecurableObjectDTO {

  @JsonProperty("fullName")
  private final String fullName;

  @JsonProperty("type")
  private final MetadataObject.Type type;

  @JsonProperty("privileges")
  private final List<String> privileges;

  /**
   * Creates a lightweight auth/me securable object.
   *
   * @param fullName Metadata object full name.
   * @param type Metadata object type.
   * @param privileges Distinct ALLOW privilege names (may be empty).
   */
  public AuthMeSecurableObjectDTO(
      String fullName, MetadataObject.Type type, List<String> privileges) {
    Preconditions.checkArgument(StringUtils.isNotBlank(fullName), "fullName cannot be blank");
    Preconditions.checkArgument(type != null, "type cannot be null");
    this.fullName = fullName;
    this.type = type;
    this.privileges = privileges == null ? Collections.emptyList() : privileges;
  }

  /** Jackson deserializer constructor. */
  public AuthMeSecurableObjectDTO() {
    this.fullName = null;
    this.type = null;
    this.privileges = Collections.emptyList();
  }
}
