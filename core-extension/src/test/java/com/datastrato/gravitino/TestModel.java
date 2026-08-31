/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino;

import org.apache.gravitino.connector.BaseModel;

public class TestModel extends BaseModel {

  public static class Builder extends BaseModelBuilder<Builder, TestModel> {

    private Builder() {}

    @Override
    protected TestModel internalBuild() {
      TestModel model = new TestModel();
      model.name = name;
      model.comment = comment;
      model.properties = properties;
      model.latestVersion = latestVersion;
      model.auditInfo = auditInfo;
      return model;
    }
  }

  public static Builder builder() {
    return new Builder();
  }
}
