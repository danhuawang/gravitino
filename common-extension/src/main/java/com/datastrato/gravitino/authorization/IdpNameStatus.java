/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.authorization;

import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.exceptions.NoSuchMetalakeException;

/**
 * A built-in IdP identity name and whether it is already present in a metalake.
 *
 * <p>{@code status} is {@code true} when the name already exists in that metalake.
 */
public final class IdpNameStatus {

  private final String name;
  private final boolean status;

  /**
   * Creates a name/status pair.
   *
   * @param name The IdP user or group name.
   * @param status Whether the name is already added to the metalake.
   */
  public IdpNameStatus(String name, boolean status) {
    this.name = name;
    this.status = status;
  }

  /**
   * @return The IdP user or group name.
   */
  public String name() {
    return name;
  }

  /**
   * @return {@code true} if the name is already added to the metalake.
   */
  public boolean status() {
    return status;
  }

  /**
   * Converts JOIN rows to status pairs.
   *
   * <p>Zero rows means the metalake does not exist. A single row with a blank name means the
   * metalake exists and the IdP has no identities.
   *
   * @param rows JOIN result rows.
   * @param metalake The metalake name, used in the missing-metalake error.
   * @return Status rows.
   * @throws NoSuchMetalakeException If {@code rows} is null or empty.
   */
  public static IdpNameStatus[] fromJoinResult(List<IdpNameStatus> rows, String metalake) {
    if (rows == null || rows.isEmpty()) {
      throw new NoSuchMetalakeException("Metalake %s does not exist", metalake);
    }
    if (rows.size() == 1 && StringUtils.isBlank(rows.get(0).name())) {
      return new IdpNameStatus[0];
    }
    return rows.toArray(new IdpNameStatus[0]);
  }
}
