/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.transit.kms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datastrato.gravitino.transit.common.TransitAuthenticationException;
import com.datastrato.gravitino.transit.common.TransitConfigurationException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.encryption.kms.KmsAuthenticationException;
import org.apache.gravitino.encryption.kms.KmsConfigurationException;
import org.junit.jupiter.api.Test;

public class TestTransitKmsClientFactorySupport {

  private static final String PROVIDER_NAME = "Test Transit";

  @Test
  void delegatesValidConfigurationParsing() {
    Map<String, String> properties = new HashMap<>();
    properties.put("endpoint", " https://localhost:8200 ");

    TransitKmsClientFactorySupport.validateSource(PROVIDER_NAME, "primary");
    TransitKmsClientFactorySupport.validateProperties(
        PROVIDER_NAME, properties, Collections.singleton("endpoint"));
    assertEquals(
        "https://localhost:8200",
        TransitKmsClientFactorySupport.requireProperty(PROVIDER_NAME, properties, "endpoint"));
    assertEquals(
        URI.create("https://localhost:8200"),
        TransitKmsClientFactorySupport.parseServiceAddress(
            PROVIDER_NAME, "https://localhost:8200/"));
    assertEquals(
        "hvs.test-token",
        TransitKmsClientFactorySupport.resolveEnvironmentCredential(
            PROVIDER_NAME, "TRANSIT_TOKEN", ignored -> "hvs.test-token"));
    TransitKmsClientFactorySupport.validateTransitMount(PROVIDER_NAME, "team/transit");
  }

  @Test
  void translatesSharedConfigurationFailuresToKmsContract() {
    KmsConfigurationException exception =
        assertThrows(
            KmsConfigurationException.class,
            () -> TransitKmsClientFactorySupport.validateSource(PROVIDER_NAME, " "));

    assertEquals("Test Transit source cannot be blank", exception.getMessage());
    assertInstanceOf(TransitConfigurationException.class, exception.getCause());
  }

  @Test
  void translatesEveryDelegatedValidationFailure() {
    assertKmsConfigurationFailure(
        () ->
            TransitKmsClientFactorySupport.validateProperties(
                PROVIDER_NAME, null, Collections.emptySet()));
    assertKmsConfigurationFailure(
        () ->
            TransitKmsClientFactorySupport.requireProperty(
                PROVIDER_NAME, Collections.emptyMap(), "endpoint"));
    assertKmsConfigurationFailure(
        () -> TransitKmsClientFactorySupport.parseServiceAddress(PROVIDER_NAME, "file:///token"));
    assertKmsConfigurationFailure(
        () ->
            TransitKmsClientFactorySupport.resolveEnvironmentCredential(
                PROVIDER_NAME, "INVALID-NAME", ignored -> "unused-token"));
    assertKmsConfigurationFailure(
        () -> TransitKmsClientFactorySupport.validateTransitMount(PROVIDER_NAME, "/transit"));
  }

  @Test
  void translatesOptionalBooleanAndPlaintextHttpPolicy() {
    assertTrue(
        TransitKmsClientFactorySupport.optionalBooleanProperty(
            PROVIDER_NAME, Map.of("allowInsecure", "true"), "allowInsecure", false));
    assertFalse(
        TransitKmsClientFactorySupport.optionalBooleanProperty(
            PROVIDER_NAME, Map.of(), "allowInsecure", false));
    assertKmsConfigurationFailure(
        () ->
            TransitKmsClientFactorySupport.optionalBooleanProperty(
                PROVIDER_NAME, Map.of("allowInsecure", "invalid"), "allowInsecure", false));
    assertKmsConfigurationFailure(
        () ->
            TransitKmsClientFactorySupport.parseServiceAddress(
                PROVIDER_NAME, "http://127.0.0.1:8200"));
    assertEquals(
        URI.create("http://127.0.0.1:8200"),
        TransitKmsClientFactorySupport.parseServiceAddress(
            PROVIDER_NAME, "http://127.0.0.1:8200", true));
  }

  @Test
  void translatesEnvironmentCredentialFailuresToKmsAuthenticationContract() {
    String secret = "do-not-disclose-this-secret\ninvalid";
    KmsAuthenticationException exception =
        assertThrows(
            KmsAuthenticationException.class,
            () ->
                TransitKmsClientFactorySupport.resolveEnvironmentCredential(
                    PROVIDER_NAME, "TRANSIT_TOKEN", ignored -> secret));

    assertInstanceOf(TransitAuthenticationException.class, exception.getCause());
    assertFalse(exception.toString().contains(secret));
    assertFalse(exception.getCause().toString().contains(secret));
  }

  private static void assertKmsConfigurationFailure(Runnable operation) {
    KmsConfigurationException exception =
        assertThrows(KmsConfigurationException.class, operation::run);
    assertInstanceOf(TransitConfigurationException.class, exception.getCause());
  }
}
