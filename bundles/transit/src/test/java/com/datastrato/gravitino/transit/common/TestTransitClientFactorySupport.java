/*
 * Copyright 2026 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.transit.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests shared Transit factory validation. */
public class TestTransitClientFactorySupport {

  @Test
  void acceptsCanonicalSource() {
    assertDoesNotThrow(
        () -> TransitClientFactorySupport.validateSource("Transit test provider", "primary"));
  }

  @Test
  void rejectsBlankOrNoncanonicalSource() {
    for (String source : new String[] {"", " ", " primary "}) {
      assertThrows(
          TransitConfigurationException.class,
          () -> TransitClientFactorySupport.validateSource("Transit test provider", source));
    }
    assertThrows(
        TransitConfigurationException.class,
        () -> TransitClientFactorySupport.validateSource("Transit test provider", null));
  }

  @Test
  void validatesSupportedProperties() {
    assertDoesNotThrow(
        () ->
            TransitClientFactorySupport.validateProperties(
                "Transit test provider", Map.of("endpoint", "value"), Set.of("endpoint")));
    assertThrows(
        TransitConfigurationException.class,
        () ->
            TransitClientFactorySupport.validateProperties(
                "Transit test provider", null, Set.of("endpoint")));
    assertThrows(
        TransitConfigurationException.class,
        () ->
            TransitClientFactorySupport.validateProperties(
                "Transit test provider", Map.of("unsupported", "value"), Set.of("endpoint")));
  }

  @Test
  void requiresAndTrimsProperties() {
    assertEquals(
        "value",
        TransitClientFactorySupport.requireProperty(
            "Transit test provider", Map.of("endpoint", " value "), "endpoint"));
    assertThrows(
        TransitConfigurationException.class,
        () ->
            TransitClientFactorySupport.requireProperty(
                "Transit test provider", Map.of(), "endpoint"));
    assertThrows(
        TransitConfigurationException.class,
        () ->
            TransitClientFactorySupport.requireProperty(
                "Transit test provider", Map.of("endpoint", " "), "endpoint"));
  }

  @Test
  void parsesOptionalBooleanPropertiesStrictly() {
    assertTrue(
        TransitClientFactorySupport.optionalBooleanProperty(
            "Transit test provider", Map.of(), "allowInsecure", true));
    assertTrue(
        TransitClientFactorySupport.optionalBooleanProperty(
            "Transit test provider", Map.of("allowInsecure", " TRUE "), "allowInsecure", false));
    assertFalse(
        TransitClientFactorySupport.optionalBooleanProperty(
            "Transit test provider", Map.of("allowInsecure", "false"), "allowInsecure", true));
    assertThrows(
        TransitConfigurationException.class,
        () ->
            TransitClientFactorySupport.optionalBooleanProperty(
                "Transit test provider", Map.of("allowInsecure", "yes"), "allowInsecure", false));
  }

  @Test
  void resolvesEnvironmentCredentialExactlyOnceWithoutTrimming() {
    AtomicInteger lookups = new AtomicInteger();
    String token =
        TransitClientFactorySupport.resolveEnvironmentCredential(
            "Transit test provider",
            "GRAVITINO_TRANSIT_TOKEN",
            name -> {
              assertEquals("GRAVITINO_TRANSIT_TOKEN", name);
              lookups.incrementAndGet();
              return "hvs.exact-token";
            });

    assertEquals("hvs.exact-token", token);
    assertEquals(1, lookups.get());
  }

  @Test
  void rejectsInvalidEnvironmentVariableNames() {
    for (String name : new String[] {"", " ", "9TOKEN", "TOKEN-NAME", "TOKEN.NAME"}) {
      assertThrows(
          TransitConfigurationException.class,
          () ->
              TransitClientFactorySupport.resolveEnvironmentCredential(
                  "Transit test provider", name, ignored -> "unused-token"));
    }
    assertThrows(
        TransitConfigurationException.class,
        () ->
            TransitClientFactorySupport.resolveEnvironmentCredential(
                "Transit test provider", null, ignored -> "unused-token"));
    assertThrows(
        TransitConfigurationException.class,
        () ->
            TransitClientFactorySupport.resolveEnvironmentCredential(
                "Transit test provider", "VALID_NAME", null));
  }

  @Test
  void rejectsMissingOrMalformedEnvironmentCredentialsWithoutDisclosingThem() {
    for (String token :
        new String[] {
          null, "", " ", " token", "token ", "token\nvalue", "token\rvalue", "a\u0000b"
        }) {
      assertThrows(
          TransitAuthenticationException.class,
          () ->
              TransitClientFactorySupport.resolveEnvironmentCredential(
                  "Transit test provider", "VALID_NAME", ignored -> token));
    }

    String malformedSecret = "do-not-disclose-this-secret\ninvalid";
    TransitAuthenticationException malformedException =
        assertThrows(
            TransitAuthenticationException.class,
            () ->
                TransitClientFactorySupport.resolveEnvironmentCredential(
                    "Transit test provider", "VALID_NAME", ignored -> malformedSecret));
    assertFalse(malformedException.toString().contains(malformedSecret));

    String oversizedToken = "t".repeat(16 * 1024 + 1);
    TransitAuthenticationException exception =
        assertThrows(
            TransitAuthenticationException.class,
            () ->
                TransitClientFactorySupport.resolveEnvironmentCredential(
                    "Transit test provider", "VALID_NAME", ignored -> oversizedToken));
    assertFalse(exception.toString().contains(oversizedToken));
  }

  @Test
  void redactsEnvironmentLookupFailures() {
    String secret = "secret-from-lookup";
    TransitAuthenticationException exception =
        assertThrows(
            TransitAuthenticationException.class,
            () ->
                TransitClientFactorySupport.resolveEnvironmentCredential(
                    "Transit test provider",
                    "VALID_NAME",
                    ignored -> {
                      throw new IllegalStateException(secret);
                    }));

    assertFalse(exception.getMessage().contains(secret));
    assertNull(exception.getCause());
  }

  @Test
  void parsesHttpOrigins() {
    assertEquals(
        URI.create("https://vault.example:8200"),
        TransitClientFactorySupport.parseServiceAddress(
            "Transit test provider", "https://vault.example:8200/"));
    assertEquals(
        URI.create("http://127.0.0.1:8200"),
        TransitClientFactorySupport.parseServiceAddress(
            "Transit test provider", "http://127.0.0.1:8200", true));
  }

  @Test
  void rejectsPlaintextHttpWithoutExplicitOptIn() {
    assertThrows(
        TransitConfigurationException.class,
        () ->
            TransitClientFactorySupport.parseServiceAddress(
                "Transit test provider", "http://127.0.0.1:8200"));
  }

  @Test
  void rejectsInvalidServiceAddresses() {
    assertThrows(
        TransitConfigurationException.class,
        () -> TransitClientFactorySupport.parseServiceAddress("Transit test provider", null));
    for (String address :
        new String[] {
          "",
          "vault.example:8200",
          "https://[invalid",
          "ftp://vault.example",
          "https://user@vault.example",
          "https://vault.example/v1",
          "https://vault.example?query=true",
          "https://vault.example#fragment"
        }) {
      assertThrows(
          TransitConfigurationException.class,
          () -> TransitClientFactorySupport.parseServiceAddress("Transit test provider", address));
    }
  }

  @Test
  void validatesTransitMounts() {
    assertDoesNotThrow(
        () -> TransitClientFactorySupport.validateTransitMount("Transit test provider", "transit"));
    assertDoesNotThrow(
        () ->
            TransitClientFactorySupport.validateTransitMount(
                "Transit test provider", "team/transit"));

    assertThrows(
        TransitConfigurationException.class,
        () -> TransitClientFactorySupport.validateTransitMount("Transit test provider", null));
    for (String mount :
        new String[] {
          "",
          "/transit",
          "transit/",
          "team//transit",
          ".",
          "..",
          "team/./transit",
          "team/../transit"
        }) {
      assertThrows(
          TransitConfigurationException.class,
          () -> TransitClientFactorySupport.validateTransitMount("Transit test provider", mount));
    }
  }
}
