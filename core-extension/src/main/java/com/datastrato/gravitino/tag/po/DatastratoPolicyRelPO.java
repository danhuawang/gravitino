/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.tag.po;

import lombok.Getter;

/** Relational PO for policy relation joined with policy metadata and version info. */
@Getter
public class DatastratoPolicyRelPO {

  private Long metadataObjectId;
  private String metadataObjectType;
  private Long policyId;
  private String policyName;
  private Long metalakeId;
  private String policyType;
  private String auditInfo;
  private Long currentVersion;
  private Long lastVersion;
  private Long deletedAt;

  private Long versionId;
  private Long versionMetalakeId;
  private Long versionPolicyId;
  private Long version;
  private String policyComment;
  private Boolean enabled;
  private String content;
  private Long versionDeletedAt;
}
