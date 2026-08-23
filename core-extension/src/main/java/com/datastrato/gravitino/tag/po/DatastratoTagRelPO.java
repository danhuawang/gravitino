/*
 * Copyright 2026 Datastrato Pvt Ltd.
 */
package com.datastrato.gravitino.tag.po;

import lombok.Getter;

/** Relational PO for tag relation joined with tag metadata. */
@Getter
public class DatastratoTagRelPO {

  private Long metadataObjectId;
  private String metadataObjectType;
  private Long tagId;
  private String tagName;
  private Long metalakeId;
  private String comment;
  private String properties;
  private String auditInfo;
  private Long currentVersion;
  private Long lastVersion;
  private Long deletedAt;
}
