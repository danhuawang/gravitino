/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino;

import org.apache.gravitino.connector.BaseModelVersion;

public class TestModelVersion extends BaseModelVersion {

  public static class Builder extends BaseModelVersionBuilder<Builder, TestModelVersion> {

    private Builder() {}

    @Override
    protected TestModelVersion internalBuild() {
      TestModelVersion modelVersion = new TestModelVersion();
      modelVersion.version = version;
      modelVersion.comment = comment;
      modelVersion.aliases = aliases;
      modelVersion.uris = uris;
      modelVersion.properties = properties;
      modelVersion.auditInfo = auditInfo;
      return modelVersion;
    }
  }

  public static Builder builder() {
    return new Builder();
  }
}
