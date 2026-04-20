/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestLicenseException {
  @Test
  void testErrorCodeMessage() {
    LicenseException ex = new LicenseException(LicenseException.ErrorCode.MISSING);
    Assertions.assertEquals(LicenseException.ErrorCode.MISSING, ex.getErrorCode());
    Assertions.assertTrue(ex.getMessage().contains("MISSING"));
  }

  @Test
  void testExpiredWithDetail() {
    LicenseException ex =
        new LicenseException(LicenseException.ErrorCode.EXPIRED, "expired 5 days ago");
    Assertions.assertEquals(LicenseException.ErrorCode.EXPIRED, ex.getErrorCode());
    Assertions.assertTrue(ex.getMessage().contains("expired 5 days ago"));
  }
}
