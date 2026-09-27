/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.gravitino.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One written audit line read back into the fields tests assert on. It understands the three
 * renderings: {@code JsonAuditFormatter} (a JSON object), {@code SimpleFormatterV2} (eight
 * tab-separated fields, custom info last as {@code {k=v, ...}}) and the v1 {@code SimpleFormatter}
 * (five fields, no custom info).
 *
 * <p>The simple renderings do not escape {@code ", "} or {@code "="} inside values, so tests that
 * parse them must use values without those sequences.
 */
public final class WrittenAuditLine {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String raw;
  private final String operation;
  private final String status;
  private final Map<String, String> customInfo;

  private WrittenAuditLine(
      String raw, String operation, String status, Map<String, String> customInfo) {
    this.raw = raw;
    this.operation = operation;
    this.status = status;
    this.customInfo = customInfo;
  }

  /**
   * Parses a line a {@link CapturingAuditLogWriter} captured.
   *
   * @param line the written line
   * @return the parsed line
   */
  public static WrittenAuditLine parse(String line) {
    return line.startsWith("{") ? parseJson(line) : parseTabSeparated(line);
  }

  /**
   * Returns the line exactly as written.
   *
   * @return the raw line
   */
  public String raw() {
    return raw;
  }

  /**
   * Returns the operation the line names: the operation type for the v2 renderings, the audit
   * operation for v1; both spell a table create {@code CREATE_TABLE}.
   *
   * @return the operation
   */
  public String operation() {
    return operation;
  }

  /**
   * Returns the status the line names, {@code SUCCESS} or {@code FAILURE}.
   *
   * @return the status
   */
  public String status() {
    return status;
  }

  /**
   * Returns the custom info as written, after redaction; empty when the rendering has none.
   *
   * @return the written custom info
   */
  public Map<String, String> customInfo() {
    return customInfo;
  }

  private static WrittenAuditLine parseJson(String line) {
    try {
      JsonNode node = MAPPER.readTree(line);
      Map<String, String> info =
          MAPPER.convertValue(node.get("customInfo"), new TypeReference<Map<String, String>>() {});
      return new WrittenAuditLine(
          line,
          node.get("operationType").asText(),
          node.get("operationStatus").asText(),
          ImmutableMap.copyOf(info));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Not a JSON audit line: " + line, e);
    }
  }

  private static WrittenAuditLine parseTabSeparated(String line) {
    String[] fields = line.split("\t", -1);
    if (fields.length == 5) {
      return new WrittenAuditLine(line, fields[2], fields[4], ImmutableMap.of());
    }
    if (fields.length != 8) {
      throw new IllegalArgumentException("Unexpected audit line layout: " + line);
    }
    Map<String, String> info = new LinkedHashMap<>();
    String rendered = fields[7];
    if (!rendered.isEmpty()) {
      for (String entry : rendered.substring(1, rendered.length() - 1).split(", ")) {
        int separator = entry.indexOf('=');
        info.put(entry.substring(0, separator), entry.substring(separator + 1));
      }
    }
    return new WrittenAuditLine(line, fields[2], fields[4], ImmutableMap.copyOf(info));
  }
}
