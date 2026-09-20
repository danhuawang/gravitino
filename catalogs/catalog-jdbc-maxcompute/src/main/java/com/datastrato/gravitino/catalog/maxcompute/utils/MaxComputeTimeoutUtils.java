/*
 * Copyright 2026 Datastrato Inc.
 */

package com.datastrato.gravitino.catalog.maxcompute.utils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Injects bounded connection parameters into a MaxCompute (ODPS) JDBC URL.
 *
 * <p>The Alibaba Cloud ODPS JDBC driver leaves its connection parameters effectively unbounded by
 * default (each timeout defaults to {@code -1}, "wait forever", and it retries four times). When a
 * MaxCompute catalog points at an unreachable or unresponsive backend, a connection attempt can
 * therefore take on the order of many minutes to give up. Because the search metadata sync opens
 * that connection while holding Gravitino's tree locks, the whole metalake stalls for the duration.
 *
 * <p>To bound that blast radius, this utility rewrites the {@code jdbc-url} to carry conservative
 * defaults whenever the operator has not set them explicitly. The ODPS driver reads these as URL
 * query parameters; each one it does not recognize is ignored, so injecting them is safe across
 * driver versions.
 *
 * <p>The parameters covered are the ones that can make a connection or metadata request hang
 * against a bad endpoint:
 *
 * <ul>
 *   <li>{@code retryTime} — the number of times a failed request is retried. This is the dominant
 *       factor: the total time against a bad endpoint is roughly {@code (connectTimeout +
 *       readTimeout) * (retryTime + 1)}, and the driver's default of four retries is what stretches
 *       a failure into tens of seconds. A single retry keeps the total well inside the tree-lock
 *       dead-lock checker window.
 *   <li>{@code connectTimeout} — establishing the HTTP connection to the ODPS endpoint.
 *   <li>{@code readTimeout} — reading a response after the connection is established.
 *   <li>{@code tunnelConnectTimeout} — establishing a data-tunnel connection.
 *   <li>{@code tunnelReadTimeout} — reading from a data-tunnel connection.
 * </ul>
 *
 * <p>Existing values are never overwritten: if the {@code jdbc-url} already specifies one of these
 * parameters, that value is preserved. Overriding a default must be done in the {@code jdbc-url}
 * itself (for example {@code &retryTime=2}), because these are ODPS URL query parameters.
 *
 * <p>Two details of the driver's URL handling are honored exactly, because getting them wrong would
 * silently leave the connection unbounded:
 *
 * <ul>
 *   <li><b>Parameter keys are case-sensitive.</b> The driver parses URL parameters into a map and
 *       looks them up by exact key, so {@code readTimeout} and {@code ReadTimeout} are different
 *       parameters and only the former is honored. This utility therefore checks for an existing
 *       parameter by exact case: if the operator wrote a differently-cased variant (which the
 *       driver ignores), the canonical key is still injected so the bound takes effect.
 *   <li><b>{@code odps_config} bypasses URL parameters entirely.</b> When the URL contains an
 *       {@code odps_config} parameter, the driver reads its configuration from that file and
 *       ignores every other URL parameter. Injected defaults could not take effect, so this utility
 *       refuses such a URL with a clear error rather than leaving the connection unbounded.
 * </ul>
 */
public final class MaxComputeTimeoutUtils {

  private static final Logger LOG = LoggerFactory.getLogger(MaxComputeTimeoutUtils.class);

  /** The catalog property key holding the JDBC URL. */
  public static final String JDBC_URL_KEY = "jdbc-url";

  /** The scheme prefix identifying a MaxCompute (ODPS) JDBC URL. */
  private static final String ODPS_URL_PREFIX = "jdbc:odps:";

  /**
   * The ODPS URL parameter that points at an external config file. When present, the driver reads
   * its configuration from the file and ignores all other URL parameters.
   */
  static final String ODPS_CONFIG_PARAM = "odps_config";

  /** ODPS URL parameter bounding the connection-establishment time, in seconds. */
  static final String CONNECT_TIMEOUT_PARAM = "connectTimeout";

  /** ODPS URL parameter bounding the response-read time, in seconds. */
  static final String READ_TIMEOUT_PARAM = "readTimeout";

  /** ODPS URL parameter bounding the data-tunnel connection-establishment time, in seconds. */
  static final String TUNNEL_CONNECT_TIMEOUT_PARAM = "tunnelConnectTimeout";

  /** ODPS URL parameter bounding the data-tunnel response-read time, in seconds. */
  static final String TUNNEL_READ_TIMEOUT_PARAM = "tunnelReadTimeout";

  /** ODPS URL parameter bounding the number of retries per failed request. */
  static final String RETRY_TIME_PARAM = "retryTime";

  // The values below are defaults only. Any of them can be overridden per catalog by setting the
  // corresponding parameter directly in the jdbc-url. They are tuned so that, against an
  // unreachable endpoint, the driver's worst-case total time is roughly
  //   (connectTimeout + readTimeout) * (retryTime + 1)
  // which with these defaults is about twelve seconds: comfortably inside the 30s tree-lock
  // dead-lock checker window, yet with enough headroom for a healthy endpoint to respond.

  /**
   * Default connection-establishment timeout, in seconds. Overridable via {@code connectTimeout} in
   * the {@code jdbc-url}.
   */
  static final String DEFAULT_CONNECT_TIMEOUT_SECONDS = "3";

  /**
   * Default response-read timeout, in seconds. Overridable via {@code readTimeout} in the {@code
   * jdbc-url}.
   */
  static final String DEFAULT_READ_TIMEOUT_SECONDS = "3";

  /**
   * Default number of retries per failed request. Overridable via {@code retryTime} in the {@code
   * jdbc-url}.
   *
   * <p>This is the parameter that matters most: the ODPS driver defaults to four retries, and each
   * attempt costs about {@code connectTimeout + readTimeout}, so the driver default is what turns
   * an unreachable endpoint into a tens-of-seconds hang. A single retry bounds the worst case while
   * still tolerating a transient blip.
   */
  static final String DEFAULT_RETRY_TIME = "1";

  /**
   * The default value applied to each ODPS connection parameter when the operator has not set it.
   * Data-tunnel timeouts reuse the connect/read defaults.
   */
  private static final Map<String, String> DEFAULT_PARAMETERS = new LinkedHashMap<>();

  static {
    DEFAULT_PARAMETERS.put(RETRY_TIME_PARAM, DEFAULT_RETRY_TIME);
    DEFAULT_PARAMETERS.put(CONNECT_TIMEOUT_PARAM, DEFAULT_CONNECT_TIMEOUT_SECONDS);
    DEFAULT_PARAMETERS.put(READ_TIMEOUT_PARAM, DEFAULT_READ_TIMEOUT_SECONDS);
    DEFAULT_PARAMETERS.put(TUNNEL_CONNECT_TIMEOUT_PARAM, DEFAULT_CONNECT_TIMEOUT_SECONDS);
    DEFAULT_PARAMETERS.put(TUNNEL_READ_TIMEOUT_PARAM, DEFAULT_READ_TIMEOUT_SECONDS);
  }

  private MaxComputeTimeoutUtils() {
    // Utility class, prevent instantiation.
  }

  /**
   * Returns a copy of the catalog configuration whose {@code jdbc-url} carries bounded default
   * connection parameters for any of them the operator has not set explicitly.
   *
   * <p>These are defaults, not overrides: a value the operator puts in the {@code jdbc-url} (for
   * example {@code &retryTime=2}) is always preserved. The input is returned unchanged (aside from
   * being defensively copied) when there is no {@code jdbc-url}, when the URL is not an ODPS URL,
   * or when every parameter is already present.
   *
   * @param config the catalog configuration; must not be null
   * @return a configuration whose {@code jdbc-url} has bounded connection parameters
   * @throws GravitinoRuntimeException if the {@code jdbc-url} uses {@code odps_config}, because the
   *     driver would then ignore the injected parameters and the connection could not be bounded
   */
  public static Map<String, String> withConnectionTimeouts(Map<String, String> config) {
    Map<String, String> result = new HashMap<>(config);
    String url = config.get(JDBC_URL_KEY);
    if (StringUtils.isBlank(url) || !isOdpsUrl(url)) {
      return result;
    }

    if (hasParameter(url, ODPS_CONFIG_PARAM)) {
      throw new GravitinoRuntimeException(
          "The MaxCompute jdbc-url uses '%s', which makes the ODPS driver read its configuration "
              + "from that file and ignore URL parameters. Gravitino cannot bound the connection "
              + "timeouts in this mode, so an unreachable backend would stall the metalake. Remove "
              + "'%s' and put the connection settings in the jdbc-url instead, or set bounded "
              + "timeouts (connectTimeout, readTimeout, retryTime) inside the config file.",
          ODPS_CONFIG_PARAM, ODPS_CONFIG_PARAM);
    }

    String rewritten = injectDefaultTimeouts(url);
    if (!rewritten.equals(url)) {
      result.put(JDBC_URL_KEY, rewritten);
      LOG.info(
          "Applied default MaxCompute connection parameters to jdbc-url; "
              + "an unreachable backend will now fail fast instead of hanging. "
              + "Override any default by setting it explicitly in jdbc-url.");
    }
    return result;
  }

  /**
   * Determines whether a URL is a MaxCompute (ODPS) JDBC URL.
   *
   * <p>The check mirrors the driver's own {@code acceptURL}, which requires the exact, lower-case
   * {@code jdbc:odps:} prefix with no leading whitespace. Matching it exactly avoids treating a URL
   * as ODPS here that the driver would reject anyway.
   *
   * @param url the JDBC URL
   * @return true if the URL targets the ODPS driver
   */
  private static boolean isOdpsUrl(String url) {
    return url.startsWith(ODPS_URL_PREFIX);
  }

  /**
   * Appends any missing default parameter to the URL, preserving values already present.
   *
   * @param url the ODPS JDBC URL
   * @return the URL with missing default parameters appended
   */
  private static String injectDefaultTimeouts(String url) {
    StringBuilder builder = new StringBuilder(url);
    for (Map.Entry<String, String> parameter : DEFAULT_PARAMETERS.entrySet()) {
      if (!hasParameter(url, parameter.getKey())) {
        builder
            .append(builder.indexOf("?") >= 0 ? '&' : '?')
            .append(parameter.getKey())
            .append('=')
            .append(parameter.getValue());
      }
    }
    return builder.toString();
  }

  /**
   * Determines whether the URL already carries the given query parameter.
   *
   * <p>The match is <b>case-sensitive</b>, mirroring the ODPS driver, which parses URL parameters
   * into a map and looks them up by exact key. A differently-cased variant is a different parameter
   * to the driver, so it must not suppress injection of the canonical key. The match is also
   * anchored at a parameter boundary ({@code ?} or {@code &}) so that, for example, {@code
   * readTimeout} is not treated as present because {@code tunnelReadTimeout} is.
   *
   * @param url the JDBC URL
   * @param parameter the exact query parameter name to look for
   * @return true if the parameter is already present in the URL with exactly this key
   */
  private static boolean hasParameter(String url, String parameter) {
    String needle = parameter + "=";
    int index = url.indexOf(needle);
    while (index >= 0) {
      char preceding = index == 0 ? '\0' : url.charAt(index - 1);
      if (preceding == '?' || preceding == '&') {
        return true;
      }
      index = url.indexOf(needle, index + 1);
    }
    return false;
  }
}
