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
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Helpers for secret-related entity property handling.
 *
 * <p>Name-based masking / {@code getSecrets} recovery for credential-like property keys. This
 * branch does not include a SecretManager URN write path; plaintext sensitive-named properties are
 * the only secrets recovered here.
 */
public final class SecretPropertyUtils {

  /**
   * Property keys whose names look like credentials. Matching is case-insensitive. Used to mask API
   * responses and to expose plaintext via {@code getSecrets} for undeclared / mistyped credential
   * properties.
   */
  private static final Pattern SENSITIVE_PROPERTY_KEY_PATTERN =
      Pattern.compile(".*(secret|password|token|credential|access|account).*");

  private SecretPropertyUtils() {}

  /**
   * Returns whether a property key name looks sensitive (credential-like).
   *
   * <p>A key matches when, after lower-casing, it contains {@code secret}, {@code password}, {@code
   * token}, {@code credential}, {@code access}, or {@code account} as a substring (covers Azure
   * storage account key/name and GCS service-account file paths). Underscores and hyphens are not
   * normalized; they are irrelevant because the matched keywords contain neither.
   *
   * @param key the property key
   * @return true when the key name matches the sensitive pattern
   */
  public static boolean isSensitivePropertyKey(@Nullable String key) {
    if (key == null || key.isEmpty()) {
      return false;
    }
    return SENSITIVE_PROPERTY_KEY_PATTERN.matcher(key.toLowerCase(Locale.ROOT)).matches();
  }

  /**
   * Builds a map of plaintext secret properties for {@code getSecrets}.
   *
   * <p>Includes every entry whose key matches {@link #isSensitivePropertyKey}. Declared {@code
   * hidden} properties are <strong>not</strong> included merely because they are hidden. A hidden
   * key is recovered only when its name matches {@link #isSensitivePropertyKey} (for example {@code
   * jdbc-password}). A hidden key whose name does not look sensitive (for example a path-like
   * {@code auth-file}) stays masked as {@code ******} on list/get and is absent from this map.
   *
   * <p>Normal non-sensitive properties are not included. Clients merge this map over masked {@code
   * properties()} so undeclared credential keys remain usable without leaking on list/get.
   *
   * @param rawProperties raw entity properties (may be null)
   * @return a new secret plaintext property map; never null
   */
  public static Map<String, String> buildSecrets(@Nullable Map<String, String> rawProperties) {
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
      if (isSensitivePropertyKey(key)) {
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
}
