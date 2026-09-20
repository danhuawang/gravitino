/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute.utils;

import static com.datastrato.gravitino.catalog.maxcompute.utils.MaxComputeTimeoutUtils.JDBC_URL_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MaxComputeTimeoutUtils}. */
class TestMaxComputeTimeoutUtils {

  private static final String BASE_URL =
      "jdbc:odps:http://service.cn-hangzhou.maxcompute.aliyun.com/api?project=p";

  private static Map<String, String> configWithUrl(String url) {
    Map<String, String> config = new HashMap<>();
    config.put(JDBC_URL_KEY, url);
    return config;
  }

  private static String urlOf(Map<String, String> config) {
    return config.get(JDBC_URL_KEY);
  }

  @Test
  void testInjectsAllParametersWhenNoneConfigured() {
    Map<String, String> result =
        MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(BASE_URL));
    String url = urlOf(result);

    // retryTime is the dominant control and must be injected.
    assertTrue(url.contains("retryTime=1"), url);
    assertTrue(url.contains("connectTimeout=3"), url);
    assertTrue(url.contains("readTimeout=3"), url);
    assertTrue(url.contains("tunnelConnectTimeout=3"), url);
    assertTrue(url.contains("tunnelReadTimeout=3"), url);
    // The original parameters are preserved.
    assertTrue(url.contains("project=p"), url);
  }

  @Test
  void testAppendsWithAmpersandWhenUrlAlreadyHasQuery() {
    Map<String, String> result =
        MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(BASE_URL));
    String url = urlOf(result);
    // BASE_URL already contains '?', so every injected parameter must be joined with '&'.
    assertFalse(url.contains("?retryTime"), url);
    assertTrue(url.contains("&retryTime=1"), url);
  }

  @Test
  void testAppendsWithQuestionMarkWhenUrlHasNoQuery() {
    String noQuery = "jdbc:odps:http://service.cn-hangzhou.maxcompute.aliyun.com/api";
    Map<String, String> result =
        MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(noQuery));
    String url = urlOf(result);

    // Exactly one '?' should be introduced, then subsequent parameters use '&'.
    assertEquals(noQuery.indexOf('?'), -1);
    assertTrue(url.startsWith(noQuery + "?retryTime=1"), url);
    assertEquals(1, url.chars().filter(c -> c == '?').count(), url);
    assertTrue(url.contains("&connectTimeout=3"), url);
  }

  @Test
  void testPreservesUserConfiguredRetryTime() {
    // Use a value distinct from the injected default so preserved-vs-injected is unambiguous.
    String url = BASE_URL + "&retryTime=5";
    Map<String, String> result = MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(url));
    String rewritten = urlOf(result);

    // The explicit value is kept and not duplicated.
    assertTrue(rewritten.contains("retryTime=5"), rewritten);
    assertFalse(rewritten.contains("retryTime=1"), rewritten);
    assertEquals(1, countBoundaryOccurrences(rewritten, "retryTime="), rewritten);
    // Missing parameters are still injected.
    assertTrue(rewritten.contains("connectTimeout=3"), rewritten);
  }

  @Test
  void testPreservesUserConfiguredConnectTimeout() {
    String url = BASE_URL + "&connectTimeout=30";
    Map<String, String> result = MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(url));
    String rewritten = urlOf(result);

    assertTrue(rewritten.contains("connectTimeout=30"), rewritten);
    // Count only the connectTimeout at a parameter boundary, so tunnelConnectTimeout is excluded.
    assertEquals(1, countBoundaryOccurrences(rewritten, "connectTimeout="), rewritten);
  }

  @Test
  void testDifferentlyCasedKeyDoesNotSuppressCanonicalInjection() {
    // The ODPS driver looks up URL parameters by exact key, so "ReadTimeout" is not "readTimeout"
    // and the driver ignores it. We must still inject the canonical lowercase "readTimeout" so the
    // bound actually takes effect; otherwise the read stage falls back to the driver's -1 (hang).
    String url = BASE_URL + "&ReadTimeout=99";
    Map<String, String> result = MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(url));
    String rewritten = urlOf(result);

    // The operator's differently-cased key is left as-is (we do not touch it) ...
    assertTrue(rewritten.contains("ReadTimeout=99"), rewritten);
    // ... and the canonical key the driver honors is injected with our default.
    assertTrue(rewritten.contains("&readTimeout=3"), rewritten);
  }

  @Test
  void testRejectsUrlUsingOdpsConfig() {
    // When odps_config is present the driver reads config from that file and ignores URL params, so
    // injected timeouts could not take effect. We refuse rather than leave the connection
    // unbounded.
    String url = BASE_URL + "&odps_config=/etc/odps/odps.conf";
    GravitinoRuntimeException e =
        assertThrows(
            GravitinoRuntimeException.class,
            () -> MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(url)));
    assertTrue(e.getMessage().contains("odps_config"), e.getMessage());
  }

  @Test
  void testReadTimeoutNotConfusedWithTunnelReadTimeout() {
    // Only tunnelReadTimeout is present; readTimeout must still be injected because the parameter
    // match is anchored at a '?' or '&' boundary.
    String url = BASE_URL + "&tunnelReadTimeout=7";
    Map<String, String> result = MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(url));
    String rewritten = urlOf(result);

    assertTrue(rewritten.contains("tunnelReadTimeout=7"), rewritten);
    assertTrue(rewritten.contains("&readTimeout=3"), rewritten);
    assertEquals(1, countBoundaryOccurrences(rewritten, "tunnelReadTimeout="), rewritten);
  }

  @Test
  void testFullyOverriddenUrlIsLeftUntouched() {
    // When the operator sets every parameter explicitly, nothing is injected: the values are theirs
    // to control per catalog.
    String url =
        BASE_URL
            + "&retryTime=4&connectTimeout=1&readTimeout=2"
            + "&tunnelConnectTimeout=5&tunnelReadTimeout=6";
    Map<String, String> result = MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(url));
    assertEquals(url, urlOf(result));
  }

  @Test
  void testIsIdempotent() {
    Map<String, String> once =
        MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(BASE_URL));
    Map<String, String> twice = MaxComputeTimeoutUtils.withConnectionTimeouts(once);
    assertEquals(urlOf(once), urlOf(twice));
  }

  @Test
  void testNonOdpsUrlIsLeftUnchanged() {
    String mysqlUrl = "jdbc:mysql://localhost:3306/db";
    Map<String, String> result =
        MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(mysqlUrl));
    assertEquals(mysqlUrl, urlOf(result));
  }

  @Test
  void testNonExactSchemeIsLeftUnchanged() {
    // The driver's acceptURL requires the exact lower-case "jdbc:odps:" prefix, so a URL the driver
    // would not accept must not be treated as ODPS here either. We leave it untouched rather than
    // inject parameters into a URL that will never reach the ODPS driver.
    String uppercaseScheme =
        "JDBC:ODPS:http://service.cn-hangzhou.maxcompute.aliyun.com/api?project=p";
    Map<String, String> result =
        MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl(uppercaseScheme));
    assertEquals(uppercaseScheme, urlOf(result));
  }

  @Test
  void testBlankOrMissingUrlIsLeftUnchanged() {
    assertFalse(
        MaxComputeTimeoutUtils.withConnectionTimeouts(new HashMap<>()).containsKey(JDBC_URL_KEY));

    Map<String, String> blank = MaxComputeTimeoutUtils.withConnectionTimeouts(configWithUrl("  "));
    assertEquals("  ", urlOf(blank));
  }

  @Test
  void testOtherConfigEntriesArePreserved() {
    Map<String, String> config = configWithUrl(BASE_URL);
    config.put("jdbc-user", "ak");
    config.put("jdbc-password", "sk");

    Map<String, String> result = MaxComputeTimeoutUtils.withConnectionTimeouts(config);
    assertEquals("ak", result.get("jdbc-user"));
    assertEquals("sk", result.get("jdbc-password"));
  }

  @Test
  void testInputMapIsNotMutated() {
    Map<String, String> config = configWithUrl(BASE_URL);
    MaxComputeTimeoutUtils.withConnectionTimeouts(config);
    // The original map must be untouched; the method returns a copy.
    assertEquals(BASE_URL, config.get(JDBC_URL_KEY));
  }

  /**
   * Counts occurrences of a parameter name that sit at a query-parameter boundary ({@code ?} or
   * {@code &}), so that, for example, {@code connectTimeout=} is not counted inside {@code
   * tunnelConnectTimeout=}.
   */
  private static int countBoundaryOccurrences(String url, String parameter) {
    String lowerUrl = url.toLowerCase();
    String needle = parameter.toLowerCase();
    int count = 0;
    int index = lowerUrl.indexOf(needle);
    while (index >= 0) {
      char preceding = index == 0 ? '\0' : lowerUrl.charAt(index - 1);
      if (preceding == '?' || preceding == '&') {
        count++;
      }
      index = lowerUrl.indexOf(needle, index + needle.length());
    }
    return count;
  }
}
