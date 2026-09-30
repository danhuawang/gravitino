/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.secret;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.connector.PropertiesMetadata;
import org.apache.gravitino.connector.PropertyEntry;
import org.apache.gravitino.storage.CloudStorageCredentialPropertyKeys;

/**
 * Helpers for secret-related entity property handling.
 *
 * <p>Name-based masking / {@code getSecrets} recovery for credential-like property keys. This
 * branch does not include a SecretManager URN write path; plaintext sensitive-named properties are
 * the only secrets recovered here.
 */
public final class SecretPropertyUtils {

  /** Empty metadata: every property key is undeclared (used for historical fuzzy recovery). */
  private static final PropertiesMetadata EMPTY_PROPERTIES_METADATA =
      new PropertiesMetadata() {
        @Override
        public Map<String, PropertyEntry<?>> propertyEntries() {
          return Map.of();
        }
      };

  private SecretPropertyUtils() {}

  /**
   * Replaces the sensitive property key keywords from server configuration.
   *
   * @param config server configuration
   */
  public static void configureSensitiveKeyKeywords(Config config) {
    SensitivePropertyKeyMatcher.configure(config);
  }

  /**
   * Returns whether a property key name looks sensitive (credential-like).
   *
   * <p>A key matches when, after lower-casing, it contains one of the active keywords as a literal
   * substring. The default keywords are {@code secret}, {@code password}, {@code token}, {@code
   * credential}, {@code access}, and {@code account} (covers Azure storage account key/name and GCS
   * service-account file paths). {@link Configs#SENSITIVE_KEY_KEYWORDS} replaces that set, so a
   * deployment can drop a default keyword or add another. Underscores and hyphens are not
   * normalized; they are irrelevant because the default keywords contain neither.
   *
   * @param key the property key
   * @return true when the key name matches an active keyword
   */
  public static boolean isSensitivePropertyKey(@Nullable String key) {
    if (key == null || key.isEmpty()) {
      return false;
    }
    return SensitivePropertyKeyMatcher.matches(key.toLowerCase(Locale.ROOT));
  }

  /**
   * Returns whether an inline (non-URN) property should be recovered via {@code getSecrets}.
   *
   * <p>For inline plaintext:
   *
   * <ul>
   *   <li>{@code metadata == null}: do <strong>not</strong> recover. Used when properties metadata
   *       is unavailable.
   *   <li>declared {@code hidden}: always recover, without the sensitive-keyword gate — so
   *       shortening {@link Configs#SENSITIVE_KEY_KEYWORDS} cannot leave a masked property without
   *       a recovery path.
   *   <li>undeclared: recover only when the key name matches {@link #isSensitivePropertyKey}
   *       (historical fuzzy recovery).
   *   <li>declared non-hidden: never recover here (for example {@code credential-providers} stays
   *       in {@code properties()}).
   * </ul>
   *
   * <p>Callers that need historical fuzzy recovery without real metadata should pass an empty
   * {@link PropertiesMetadata} (every key is undeclared) — see the one-argument {@link
   * #buildSecrets(Map)}.
   *
   * @param key the property key
   * @param metadata entity properties metadata, or null when unavailable
   * @return true when the inline plaintext should be included in {@code getSecrets}
   */
  public static boolean shouldRecoverSensitiveNamedSecret(
      String key, @Nullable PropertiesMetadata metadata) {
    if (metadata == null) {
      return false;
    }
    if (metadata.containsProperty(key)) {
      return metadata.isHiddenProperty(key);
    }
    return isSensitivePropertyKey(key);
  }

  /**
   * Builds a map of plaintext secret properties for {@code getSecrets} with historical fuzzy
   * recovery for sensitive-named keys.
   *
   * <p>Delegates to {@link #buildSecrets(Map, PropertiesMetadata)} with an empty properties
   * metadata so every key is treated as undeclared. Prefer the two-argument overload when real
   * entity metadata is available.
   *
   * @param rawProperties raw entity properties (may be null)
   * @return a new secret plaintext property map; never null
   */
  public static Map<String, String> buildSecrets(@Nullable Map<String, String> rawProperties) {
    return buildSecrets(rawProperties, EMPTY_PROPERTIES_METADATA);
  }

  /**
   * Builds a map of plaintext secret properties for {@code getSecrets}.
   *
   * <p>Starting from raw entity properties, include every inline entry when {@link
   * #shouldRecoverSensitiveNamedSecret} is true: declared {@code hidden} (no keyword gate), or
   * undeclared with a sensitive-looking name. Declared non-hidden keys are excluded even when the
   * name looks sensitive. When {@code metadata} is {@code null}, inline plaintext is not recovered.
   *
   * <p>Normal non-sensitive, non-hidden properties are not included. Clients merge this map over
   * masked {@code properties()} so hidden and undeclared credential keys remain usable without
   * leaking on list/get.
   *
   * @param rawProperties raw entity properties (may be null)
   * @param metadata entity properties metadata, or null when unavailable (no recovery)
   * @return a new secret plaintext property map; never null
   */
  public static Map<String, String> buildSecrets(
      @Nullable Map<String, String> rawProperties, @Nullable PropertiesMetadata metadata) {
    if (rawProperties == null || rawProperties.isEmpty()) {
      return Map.of();
    }
    Map<String, String> secrets = new HashMap<>();
    for (Map.Entry<String, String> entry : rawProperties.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || value == null) {
        continue;
      }
      if (shouldRecoverSensitiveNamedSecret(key, metadata)) {
        secrets.put(key, value);
      }
    }
    return secrets;
  }

  /**
   * Merges base properties with secret plaintext properties.
   *
   * <p>Returns a new mutable map containing all entries from {@code base}, then overlays {@code
   * secrets}. Null maps are treated as empty.
   *
   * @param base non-secret / default-load properties (may be null)
   * @param secrets secret plaintext properties from {@link #buildSecrets} (may be null)
   * @return a new mutable merged property map; never null
   */
  public static Map<String, String> mergeProperties(
      @Nullable Map<String, String> base, @Nullable Map<String, String> secrets) {
    Map<String, String> merged = copyEntityProperties(base);
    if (secrets != null && !secrets.isEmpty()) {
      merged.putAll(secrets);
    }
    return merged;
  }

  /**
   * Returns a mutable copy of a property map.
   *
   * <p>{@code null} becomes an empty {@link HashMap}; otherwise returns a new {@link HashMap} copy.
   *
   * @param properties property map to copy (may be null)
   * @return a mutable property map, never null
   */
  public static Map<String, String> copyEntityProperties(@Nullable Map<String, String> properties) {
    return properties == null ? new HashMap<>() : new HashMap<>(properties);
  }

  /**
   * Omits cloud access-key pair properties from a plaintext secrets map for {@code USE_SECRETS}
   * callers. Delegates to {@link
   * CloudStorageCredentialPropertyKeys#omitCloudAccessKeyPairProperties}.
   *
   * @param secrets plaintext secrets
   * @return secrets without cloud AK/SK pairs
   */
  public static Map<String, String> omitCloudAccessKeyPairSecrets(
      @Nullable Map<String, String> secrets) {
    return CloudStorageCredentialPropertyKeys.omitCloudAccessKeyPairProperties(secrets);
  }
}
