/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.license.mapper.provider.h2;

import com.datastrato.gravitino.license.mapper.provider.base.LicenseNodeBaseSQLProvider;

// H2 in MySQL compatibility mode supports ON DUPLICATE KEY UPDATE
public class LicenseNodeH2SQLProvider extends LicenseNodeBaseSQLProvider {}
