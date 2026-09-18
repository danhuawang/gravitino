/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.iceberg.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;

/** Executes catalog-local mutations with shared transaction cleanup and commit classification. */
final class JdbcMetadataIntegrityTransaction {

  private JdbcMetadataIntegrityTransaction() {}

  static <T> T run(Connection connection, String commitContext, SqlOperation<T> operation)
      throws SQLException {
    boolean previousAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    boolean commitAttempted = false;
    try {
      T result = operation.run(connection);
      commitAttempted = true;
      connection.commit();
      connection.setAutoCommit(previousAutoCommit);
      return result;
    } catch (SQLException | RuntimeException e) {
      if (rollback(connection, e)) {
        if (!restoreAutoCommit(connection, previousAutoCommit, e)) {
          closeAfterFailedCleanup(connection, e);
        }
      } else {
        closeAfterFailedCleanup(connection, e);
      }
      if (commitAttempted && e instanceof SQLException) {
        throw new CommitStateUnknownException(
            "JDBC failure after attempting to commit " + commitContext, e);
      }
      throw e;
    }
  }

  static void closeAfterFailedCleanup(Connection connection, Throwable transactionFailure) {
    try {
      connection.close();
    } catch (SQLException closeFailure) {
      transactionFailure.addSuppressed(closeFailure);
    }
  }

  private static boolean rollback(Connection connection, Throwable failure) {
    try {
      connection.rollback();
      return true;
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
      return false;
    }
  }

  private static boolean restoreAutoCommit(
      Connection connection, boolean previousAutoCommit, Throwable failure) {
    try {
      connection.setAutoCommit(previousAutoCommit);
      return true;
    } catch (SQLException restoreFailure) {
      failure.addSuppressed(restoreFailure);
      return false;
    }
  }

  @FunctionalInterface
  interface SqlOperation<T> {
    T run(Connection connection) throws SQLException;
  }
}
