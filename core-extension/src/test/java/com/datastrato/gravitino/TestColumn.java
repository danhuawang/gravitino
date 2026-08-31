/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.gravitino.connector.BaseColumn;

@EqualsAndHashCode(callSuper = true)
@ToString
public class TestColumn extends BaseColumn {

  private TestColumn() {}

  public static class Builder extends BaseColumn.BaseColumnBuilder<Builder, TestColumn> {
    /** Creates a new instance of {@link Builder}. */
    private Builder() {}

    @Override
    protected TestColumn internalBuild() {
      TestColumn column = new TestColumn();

      column.name = name;
      column.comment = comment;
      column.dataType = dataType;
      column.nullable = nullable;
      column.defaultValue = defaultValue;

      return column;
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
