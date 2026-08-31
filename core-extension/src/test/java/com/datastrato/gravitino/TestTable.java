/*
 * Copyright 2024 Datastrato Inc.
 */
package com.datastrato.gravitino;

import lombok.EqualsAndHashCode;
import org.apache.gravitino.connector.BaseTable;
import org.apache.gravitino.connector.TableOperations;
import org.apache.gravitino.rel.SupportsPartitions;

@EqualsAndHashCode(callSuper = true)
public class TestTable extends BaseTable {
  @Override
  protected TableOperations newOps() {
    return new TestTableOperations();
  }

  @Override
  public SupportsPartitions supportPartitions() throws UnsupportedOperationException {
    return (SupportsPartitions) ops();
  }

  public static class Builder extends BaseTable.BaseTableBuilder<Builder, TestTable> {

    /** Creates a new instance of {@link Builder}. */
    private Builder() {}

    @Override
    protected TestTable internalBuild() {
      TestTable table = new TestTable();
      table.name = name;
      table.comment = comment;
      table.properties = properties;
      table.columns = columns;
      table.auditInfo = auditInfo;
      table.distribution = distribution;
      table.sortOrders = sortOrders;
      table.partitioning = partitioning;
      table.indexes = indexes;
      table.proxyPlugin = proxyPlugin;
      return table;
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
