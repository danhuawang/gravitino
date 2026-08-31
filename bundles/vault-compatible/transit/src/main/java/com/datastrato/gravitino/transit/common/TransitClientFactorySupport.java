/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.transit.common;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Shared configuration validation for Transit-compatible client factories. */
public final class TransitClientFactorySupport {

  private static final int MAX_BEARER_TOKEN_CHARACTERS = 16 * 1024;
  private static final Pattern ENVIRONMENT_VARIABLE_NAME =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private TransitClientFactorySupport() {}

  /**
   * Validates a configured source.
   *
   * @param providerName provider name used in errors
   * @param source configured source
   */
  public static void validateSource(String providerName, String source) {
    if (source == null || source.trim().isEmpty()) {
      throw new TransitConfigurationException(
          String.format("%s source cannot be blank", providerName));
    }
    if (!source.equals(source.trim())) {
      throw new TransitConfigurationException(
          String.format("%s source cannot have surrounding whitespace", providerName));
    }
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
    if (properties == null) {
      throw new TransitConfigurationException(
          String.format("%s properties cannot be null", providerName));
    }
    for (String property : properties.keySet()) {
      if (!supportedProperties.contains(property)) {
        throw new TransitConfigurationException(
            String.format("Unsupported %s property: %s", providerName, property));
      }
    }
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
    String value = properties.get(name);
    if (value == null || value.trim().isEmpty()) {
      throw new TransitConfigurationException(
          String.format("Missing required %s property: %s", providerName, name));
    }
    return value.trim();
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
    String value = properties.get(name);
    if (value == null) {
      return defaultValue;
    }
    String canonicalValue = value.trim();
    if ("true".equalsIgnoreCase(canonicalValue)) {
      return true;
    }
    if ("false".equalsIgnoreCase(canonicalValue)) {
      return false;
    }
    throw new TransitConfigurationException(
        String.format("Invalid %s boolean property: %s", providerName, name));
  }

  /**
   * Parses an HTTP(S) service base address.
   *
   * @param providerName provider name used in errors
   * @param value configured address
   * @return normalized service address
   */
  public static URI parseServiceAddress(String providerName, String value) {
    return parseServiceAddress(providerName, value, false);
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
    if (value == null) {
      throw new TransitConfigurationException(
          String.format("Invalid %s endpoint address", providerName));
    }
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new TransitConfigurationException(
          e, String.format("Invalid %s endpoint address", providerName));
    }

    String path = uri.getPath();
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(path == null || path.isEmpty() || "/".equals(path))) {
      throw new TransitConfigurationException(
          String.format(
              "%s endpoint address must be an HTTP(S) server base address", providerName));
    }
    if ("http".equalsIgnoreCase(uri.getScheme()) && !allowInsecureHttp) {
      throw new TransitConfigurationException(
          String.format(
              "%s endpoint address must use HTTPS unless insecure HTTP is explicitly allowed",
              providerName));
    }

    String address = uri.toString();
    return URI.create(address.endsWith("/") ? address.substring(0, address.length() - 1) : address);
  }

  /**
   * Resolves and validates a bearer token from a named process environment variable.
   *
   * <p>The value is returned exactly as supplied. It is never trimmed or included in an error.
   *
   * @param providerName provider name used in errors
   * @param variableName configured environment-variable name
   * @param environmentLookup environment lookup used by the provider factory
   * @return validated bearer token
   */
  public static String resolveEnvironmentCredential(
      String providerName, String variableName, Function<String, String> environmentLookup) {
    validateEnvironmentVariableName(providerName, variableName);
    if (environmentLookup == null) {
      throw new TransitConfigurationException(
          String.format("Invalid %s environment lookup", providerName));
    }

    String token;
    try {
      token = environmentLookup.apply(variableName);
    } catch (RuntimeException ignored) {
      throw new TransitAuthenticationException(
          String.format("Failed to resolve the %s bearer token", providerName));
    }
    return validateBearerToken(providerName, token);
  }

  /**
   * Validates a configured process environment-variable name.
   *
   * @param providerName provider name used in errors
   * @param variableName configured environment-variable name
   */
  public static void validateEnvironmentVariableName(String providerName, String variableName) {
    if (variableName == null || !ENVIRONMENT_VARIABLE_NAME.matcher(variableName).matches()) {
      throw new TransitConfigurationException(
          String.format("Invalid %s credential environment variable name", providerName));
    }
  }

  static String validateBearerToken(String providerName, String token) {
    if (token == null
        || token.isEmpty()
        || token.length() > MAX_BEARER_TOKEN_CHARACTERS
        || containsWhitespaceOrControlCharacter(token)) {
      throw new TransitAuthenticationException(
          String.format("Invalid %s bearer token", providerName));
    }
    return token;
  }

  /**
   * Validates a Transit mount path.
   *
   * @param providerName provider name used in errors
   * @param mount configured mount path
   */
  public static void validateTransitMount(String providerName, String mount) {
    if (mount == null || mount.isEmpty() || mount.startsWith("/") || mount.endsWith("/")) {
      throw new TransitConfigurationException(String.format("Invalid %s mount path", providerName));
    }
    for (String segment : mount.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw new TransitConfigurationException(
            String.format("Invalid %s mount path", providerName));
      }
    }
  }

  private static boolean containsWhitespaceOrControlCharacter(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isWhitespace(character) || Character.isISOControl(character)) {
        return true;
      }
    }
    return false;
  }
}
