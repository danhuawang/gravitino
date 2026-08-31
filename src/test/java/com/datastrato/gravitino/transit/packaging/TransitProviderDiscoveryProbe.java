/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.transit.packaging;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import org.apache.gravitino.encryption.kms.KmsClient;
import org.apache.gravitino.encryption.kms.KmsClientFactory;

/** Verifies Transit provider discovery from the packaged Gravitino runtime classpath. */
final class TransitProviderDiscoveryProbe {

  private static final String TOKEN_ENVIRONMENT_VARIABLE = "GRAVITINO_TRANSIT_PACKAGING_TOKEN";
  private static final Set<String> EXPECTED_APIS =
      new HashSet<>(Arrays.asList("openbao-transit", "vault-transit"));

  private TransitProviderDiscoveryProbe() {}

  /**
   * Loads and constructs each packaged Transit provider without contacting a KMS.
   *
   * @param args the provider directory, generated server launcher, and expected API-to-JAR-prefix
   *     mappings
   * @throws IOException if the generated launcher cannot be read
   * @throws URISyntaxException if a provider's code source is not a valid URI
   */
  public static void main(String[] args) throws IOException, URISyntaxException {
    if (args.length < 3) {
      throw new IllegalArgumentException(
          "Expected the provider directory, launcher, and at least one API-to-JAR mapping");
    }

    Path providerDirectory = Paths.get(args[0]).toAbsolutePath().normalize();
    verifyLauncher(Paths.get(args[1]));
    Map<String, String> expectedProviders =
        expectedProviders(Arrays.copyOfRange(args, 2, args.length));
    Set<String> discoveredApis = new HashSet<>();
    int factoryCount = 0;
    for (KmsClientFactory factory : ServiceLoader.load(KmsClientFactory.class)) {
      String api = apiFor(factory);
      if (api == null || !EXPECTED_APIS.contains(api)) {
        continue;
      }
      if (!expectedProviders.containsKey(api)) {
        throw new AssertionError(
            String.format("Unexpected packaged Transit factory for API %s", api));
      }
      verifyCodeSource(factory, providerDirectory, expectedProviders.get(api));
      verifyClientConstruction(factory);
      if (!discoveredApis.add(api)) {
        throw new AssertionError(String.format("Duplicate packaged KMS factory for API %s", api));
      }
      factoryCount++;
    }

    if (factoryCount != expectedProviders.size()
        || !discoveredApis.equals(expectedProviders.keySet())) {
      throw new AssertionError(
          String.format(
              "Expected %d packaged Transit factories for %s, but found %d for %s",
              expectedProviders.size(), expectedProviders.keySet(), factoryCount, discoveredApis));
    }
  }

  private static String apiFor(KmsClientFactory factory) {
    String className = factory.getClass().getName();
    if ("com.datastrato.gravitino.transit.vault.VaultTransitKmsClientFactory".equals(className)) {
      return "vault-transit";
    }
    if ("com.datastrato.gravitino.transit.openbao.OpenBaoTransitKmsClientFactory"
        .equals(className)) {
      return "openbao-transit";
    }
    return null;
  }

  private static Map<String, String> expectedProviders(String[] mappings) {
    Map<String, String> expectedProviders = new HashMap<>();
    for (String mapping : mappings) {
      int separator = mapping.indexOf('=');
      if (separator <= 0 || separator == mapping.length() - 1) {
        throw new IllegalArgumentException("Invalid API-to-JAR mapping: " + mapping);
      }
      String api = mapping.substring(0, separator);
      String jarPrefix = mapping.substring(separator + 1);
      if (!EXPECTED_APIS.contains(api) || expectedProviders.put(api, jarPrefix) != null) {
        throw new IllegalArgumentException("Invalid or duplicate Transit API mapping: " + mapping);
      }
    }
    return expectedProviders;
  }

  private static void verifyClientConstruction(KmsClientFactory factory) {
    Map<String, String> properties = new HashMap<>();
    properties.put("endpoint.address", "http://127.0.0.1:1");
    properties.put("endpoint.allowInsecureHttp", "true");
    properties.put("endpoint.transitMount", "transit");
    properties.put("credential.method", "environment_variable");
    properties.put("credential.environmentVariable", TOKEN_ENVIRONMENT_VARIABLE);
    try (KmsClient ignored = factory.create("packaged-discovery-probe", properties)) {
      // Construction loads the relocated HTTP and JSON classes but makes no network request.
    }
  }

  private static void verifyLauncher(Path launcher) throws IOException {
    String launcherContent = new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8);
    String expectedEntry = "addJarInDir \"${GRAVITINO_HOME}/kms-providers\"";
    if (!launcherContent.contains(expectedEntry)) {
      throw new AssertionError(
          String.format("Generated server launcher %s does not load KMS providers", launcher));
    }
  }

  private static void verifyCodeSource(
      KmsClientFactory factory, Path providerDirectory, String expectedJarPrefix)
      throws URISyntaxException {
    Path codeSource =
        Paths.get(factory.getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
            .toAbsolutePath()
            .normalize();
    if (!providerDirectory.equals(codeSource.getParent())
        || !codeSource.getFileName().toString().startsWith(expectedJarPrefix)) {
      throw new AssertionError(
          String.format(
              "KMS factory %s was loaded from %s instead of %s/%s*",
              factory.getClass().getName(), codeSource, providerDirectory, expectedJarPrefix));
    }
  }
}
