/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino;

import lombok.EqualsAndHashCode;
import org.apache.gravitino.connector.BaseSchema;

@EqualsAndHashCode(callSuper = true)
public class TestSchema extends BaseSchema {
  private TestSchema() {}

  public static class Builder extends BaseSchema.BaseSchemaBuilder<Builder, TestSchema> {
    /** Creates a new instance of {@link Builder}. */
    private Builder() {}

    @Override
    protected TestSchema internalBuild() {
      TestSchema schema = new TestSchema();

      schema.name = name;
      schema.comment = comment;
      schema.properties = properties;
      schema.auditInfo = auditInfo;

      return schema;
    }
  }

  /**
   * Creates a new instance of {@link Builder}.
   *
   * @return The new instance.
   */
  public static Builder builder() {
    return new Builder();
  }
}
