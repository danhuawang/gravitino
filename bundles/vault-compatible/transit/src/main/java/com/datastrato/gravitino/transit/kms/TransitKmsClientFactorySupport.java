/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.transit.kms;

import com.datastrato.gravitino.transit.common.TransitAuthenticationException;
import com.datastrato.gravitino.transit.common.TransitClientFactorySupport;
import com.datastrato.gravitino.transit.common.TransitConfigurationException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.gravitino.encryption.kms.KmsAuthenticationException;
import org.apache.gravitino.encryption.kms.KmsConfigurationException;

/** Shared configuration validation for Transit-compatible KMS client factories. */
public final class TransitKmsClientFactorySupport {

  private TransitKmsClientFactorySupport() {}

  /**
   * Validates a configured KMS source.
   *
   * @param providerName provider name used in errors
   * @param source configured source
   */
  public static void validateSource(String providerName, String source) {
    translate(
        () -> {
          TransitClientFactorySupport.validateSource(providerName, source);
          return null;
        });
  }

  /**
   * Validates that a property map contains only supported keys.
   *
   * @param providerName provider name used in errors
   * @param properties configured properties
   * @param supportedProperties supported property names
   */
  public static void validateProperties(
      String providerName, Map<String, String> properties, Set<String> supportedProperties) {
    translate(
        () -> {
          TransitClientFactorySupport.validateProperties(
              providerName, properties, supportedProperties);
          return null;
        });
  }

  /**
   * Returns a required trimmed property value.
   *
   * @param providerName provider name used in errors
   * @param properties configured properties
   * @param name required property name
   * @return trimmed property value
   */
  public static String requireProperty(
      String providerName, Map<String, String> properties, String name) {
    return translate(
        () -> TransitClientFactorySupport.requireProperty(providerName, properties, name));
  }

  /**
   * Returns a strictly parsed optional boolean property.
   *
   * @param providerName provider name used in errors
   * @param properties configured properties
   * @param name optional property name
   * @param defaultValue value returned when the property is absent
   * @return parsed boolean value
   */
  public static boolean optionalBooleanProperty(
      String providerName, Map<String, String> properties, String name, boolean defaultValue) {
    return translate(
        () ->
            TransitClientFactorySupport.optionalBooleanProperty(
                providerName, properties, name, defaultValue));
  }

  /**
   * Parses an HTTP(S) service base address.
   *
   * @param providerName provider name used in errors
   * @param value configured address
   * @return normalized service address
   */
  public static URI parseServiceAddress(String providerName, String value) {
    return translate(() -> TransitClientFactorySupport.parseServiceAddress(providerName, value));
  }

  /**
   * Parses an HTTP(S) service base address with an explicit plaintext-HTTP policy.
   *
   * @param providerName provider name used in errors
   * @param value configured address
   * @param allowInsecureHttp whether plaintext HTTP is explicitly allowed
   * @return normalized service address
   */
  public static URI parseServiceAddress(
      String providerName, String value, boolean allowInsecureHttp) {
    return translate(
        () ->
            TransitClientFactorySupport.parseServiceAddress(
                providerName, value, allowInsecureHttp));
  }

  /**
   * Resolves a validated bearer token from a named process environment variable.
   *
   * @param providerName provider name used in errors
   * @param variableName configured environment-variable name
   * @param environmentLookup environment lookup used by the provider factory
   * @return validated bearer token
   */
  public static String resolveEnvironmentCredential(
      String providerName, String variableName, Function<String, String> environmentLookup) {
    try {
      return translate(
          () ->
              TransitClientFactorySupport.resolveEnvironmentCredential(
                  providerName, variableName, environmentLookup));
    } catch (TransitAuthenticationException e) {
      throw new KmsAuthenticationException(e, "%s", e.getMessage());
    }
  }

  /**
   * Validates a Transit mount path.
   *
   * @param providerName provider name used in errors
   * @param mount configured mount path
   */
  public static void validateTransitMount(String providerName, String mount) {
    translate(
        () -> {
          TransitClientFactorySupport.validateTransitMount(providerName, mount);
          return null;
        });
  }

  private static <T> T translate(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (TransitConfigurationException e) {
      throw new KmsConfigurationException(e, "%s", e.getMessage());
    }
  }
}
