/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.encryption.kms.KmsReference;

/** Built-in policy content for governing encryption of Iceberg tables. */
public class IcebergEncryptionContent implements PolicyContent {

  /** The supported policy content schema version. */
  public static final int CURRENT_SCHEMA_VERSION = 1;

  /** Whether encryption is required when the field is omitted. */
  public static final boolean DEFAULT_REQUIRED = true;

  /** The enforcement behavior used when the field is omitted. */
  public static final Enforcement DEFAULT_ENFORCEMENT = Enforcement.REPORT;

  /** Rule key for the policy content schema version. */
  public static final String SCHEMA_VERSION_KEY = "schemaVersion";

  /** Rule key for whether encryption is required. */
  public static final String REQUIRED_KEY = "required";

  /** Rule key for allowed KMS keys. */
  public static final String ALLOWED_KEYS_KEY = "allowedKeys";

  /** Rule key for enforcement behavior. */
  public static final String ENFORCEMENT_KEY = "enforcement";

  private static final Set<MetadataObject.Type> SUPPORTED_OBJECT_TYPES =
      ImmutableSet.of(
          MetadataObject.Type.CATALOG, MetadataObject.Type.SCHEMA, MetadataObject.Type.TABLE);

  private static final Pattern PROVIDER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

  private final int schemaVersion;
  private final boolean required;
  private final List<KmsReference> allowedKeys;
  private final Enforcement enforcement;

  /** Default constructor for Jackson deserialization only. */
  private IcebergEncryptionContent() {
    this(null, null, null, null);
  }

  IcebergEncryptionContent(
      Integer schemaVersion,
      Boolean required,
      List<KmsReference> allowedKeys,
      Enforcement enforcement) {
    this.schemaVersion = schemaVersion == null ? 0 : schemaVersion;
    this.required = required == null ? DEFAULT_REQUIRED : required;
    this.allowedKeys =
        allowedKeys == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(allowedKeys));
    this.enforcement = enforcement == null ? DEFAULT_ENFORCEMENT : enforcement;
  }

  /**
   * Returns the policy content schema version.
   *
   * @return schema version
   */
  public int schemaVersion() {
    return schemaVersion;
  }

  /**
   * Returns whether matching tables must use encryption.
   *
   * @return {@code true} if encryption is required
   */
  public boolean required() {
    return required;
  }

  /**
   * Returns the exact KMS keys allowed by this policy.
   *
   * <p>Each entry is {@code {provider, keyId}}. Protocol {@code api} is not part of the identity.
   *
   * @return immutable allowed-key list
   */
  public List<KmsReference> allowedKeys() {
    return allowedKeys;
  }

  /**
   * Returns the behavior for a noncompliant table-create request.
   *
   * @return enforcement behavior
   */
  public Enforcement enforcement() {
    return enforcement;
  }

  @Override
  public Set<MetadataObject.Type> supportedObjectTypes() {
    return SUPPORTED_OBJECT_TYPES;
  }

  @Override
  public Map<String, String> properties() {
    return ImmutableMap.of();
  }

  @Override
  public Map<String, Object> rules() {
    Map<String, Object> rules = new LinkedHashMap<>();
    rules.put(SCHEMA_VERSION_KEY, schemaVersion);
    rules.put(REQUIRED_KEY, required);
    rules.put(ALLOWED_KEYS_KEY, allowedKeys);
    rules.put(ENFORCEMENT_KEY, enforcement.value());
    return Collections.unmodifiableMap(rules);
  }

  @Override
  public void validate() throws IllegalArgumentException {
    PolicyContent.super.validate();
    Preconditions.checkArgument(
        schemaVersion == CURRENT_SCHEMA_VERSION,
        "schemaVersion must be %s",
        CURRENT_SCHEMA_VERSION);
    Preconditions.checkArgument(enforcement != null, "enforcement cannot be null");
    Preconditions.checkArgument(
        !required || !allowedKeys.isEmpty(),
        "allowedKeys cannot be empty when encryption is required");

    Set<KmsReference> uniqueKeys = new HashSet<>();
    for (KmsReference allowedKey : allowedKeys) {
      Preconditions.checkArgument(allowedKey != null, "allowedKeys cannot contain null values");
      Preconditions.checkArgument(
          PROVIDER_NAME.matcher(allowedKey.provider()).matches(),
          "KMS provider must match [A-Za-z0-9][A-Za-z0-9_-]*: '%s'",
          allowedKey.provider());
      Preconditions.checkArgument(
          uniqueKeys.add(allowedKey),
          "allowedKeys cannot contain duplicate key: %s:%s",
          allowedKey.provider(),
          allowedKey.keyId());
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof IcebergEncryptionContent)) {
      return false;
    }
    IcebergEncryptionContent that = (IcebergEncryptionContent) o;
    return schemaVersion == that.schemaVersion
        && required == that.required
        && Objects.equals(allowedKeys, that.allowedKeys)
        && enforcement == that.enforcement;
  }

  @Override
  public int hashCode() {
    return Objects.hash(schemaVersion, required, allowedKeys, enforcement);
  }

  @Override
  public String toString() {
    return "IcebergEncryptionContent{"
        + "schemaVersion="
        + schemaVersion
        + ", required="
        + required
        + ", allowedKeys="
        + allowedKeys
        + ", enforcement="
        + enforcement
        + '}';
  }

  /** Enforcement behavior for a noncompliant Iceberg table-create request. */
  public enum Enforcement {
    /** Report the violation without denying table creation. */
    REPORT("report"),

    /** Deny creation of the noncompliant table. */
    DENY_CREATE("deny-create");

    private final String value;

    Enforcement(String value) {
      this.value = value;
    }

    /**
     * Returns the stable JSON value.
     *
     * @return wire value
     */
    @JsonValue
    public String value() {
      return value;
    }

    /**
     * Parses an exact, case-sensitive enforcement value.
     *
     * @param value wire value
     * @return enforcement behavior
     * @throws IllegalArgumentException if the value is unsupported
     */
    @JsonCreator
    public static Enforcement fromValue(String value) {
      for (Enforcement enforcement : values()) {
        if (enforcement.value.equals(value)) {
          return enforcement;
        }
      }
      throw new IllegalArgumentException("Unknown enforcement: " + value);
    }
  }
}
