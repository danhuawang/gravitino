/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino.license;

public class LicenseException extends RuntimeException {

  public enum ErrorCode {
    MISSING,
    INVALID_FORMAT,
    UNKNOWN_VERSION,
    INVALID_SIGNATURE,
    EXPIRED,
    NODE_LIMIT
  }

  private final ErrorCode errorCode;

  public LicenseException(ErrorCode errorCode) {
    super(errorCode.name());
    this.errorCode = errorCode;
  }

  public LicenseException(ErrorCode errorCode, String detail) {
    super(errorCode.name() + ": " + detail);
    this.errorCode = errorCode;
  }

  public LicenseException(ErrorCode errorCode, String detail, Throwable cause) {
    super(errorCode.name() + ": " + detail, cause);
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
