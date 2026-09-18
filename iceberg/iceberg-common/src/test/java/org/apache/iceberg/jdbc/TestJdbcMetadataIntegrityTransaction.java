/*
 * Copyright 2026 Datastrato Inc.
 */
package org.apache.iceberg.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** Tests the shared JDBC transaction contract without introducing pool replay. */
class TestJdbcMetadataIntegrityTransaction {

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testSuccessfulTransactionReturnsResultAndRestoresAutoCommit(boolean previousAutoCommit)
      throws Exception {
    Connection connection = Mockito.mock(Connection.class);
    Mockito.when(connection.getAutoCommit()).thenReturn(previousAutoCommit);

    String result =
        JdbcMetadataIntegrityTransaction.run(
            connection,
            "test operation",
            current -> {
              Assertions.assertSame(connection, current);
              return "done";
            });

    Assertions.assertEquals("done", result);
    InOrder order = Mockito.inOrder(connection);
    order.verify(connection).getAutoCommit();
    order.verify(connection).setAutoCommit(false);
    order.verify(connection).commit();
    order.verify(connection).setAutoCommit(previousAutoCommit);
    Mockito.verify(connection, Mockito.never()).rollback();
    Mockito.verify(connection, Mockito.never()).close();
  }

  @Test
  void testOperationFailureRollsBackWithoutCommit() throws Exception {
    Connection connection = Mockito.mock(Connection.class);
    Mockito.when(connection.getAutoCommit()).thenReturn(true);
    SQLException expected = new SQLException("Mutation failed");
    SQLException actual =
        Assertions.assertThrows(
            SQLException.class,
            () ->
                JdbcMetadataIntegrityTransaction.run(
                    connection,
                    "test operation",
                    current -> {
                      throw expected;
                    }));

    Assertions.assertSame(expected, actual);
    Mockito.verify(connection).rollback();
    Mockito.verify(connection).setAutoCommit(true);
    Mockito.verify(connection, Mockito.never()).commit();
  }

  @Test
  void testRuntimeFailureRetainsClassification() throws Exception {
    Connection connection = Mockito.mock(Connection.class);
    IllegalStateException expected = new IllegalStateException("Rejected");
    Assertions.assertSame(
        expected,
        Assertions.assertThrows(
            IllegalStateException.class,
            () ->
                JdbcMetadataIntegrityTransaction.run(
                    connection,
                    "test operation",
                    current -> {
                      throw expected;
                    })));
    Mockito.verify(connection).rollback();
    Mockito.verify(connection, Mockito.never()).commit();
  }

  @Test
  void testCommitFailureIsUnknownWithoutReplayingOperation() throws Exception {
    Connection connection = Mockito.mock(Connection.class);
    SQLException expected = new SQLException("Commit acknowledgment lost");
    Mockito.doThrow(expected).when(connection).commit();
    AtomicInteger calls = new AtomicInteger();
    CommitStateUnknownException actual =
        Assertions.assertThrows(
            CommitStateUnknownException.class,
            () ->
                JdbcMetadataIntegrityTransaction.run(
                    connection, "test operation", current -> calls.incrementAndGet()));

    Assertions.assertSame(expected, actual.getCause());
    Assertions.assertEquals(1, calls.get());
    Mockito.verify(connection).commit();
    Mockito.verify(connection).rollback();
  }

  @Test
  void testFailedConnectionClosePreservesOriginalFailure() throws Exception {
    Connection connection = Mockito.mock(Connection.class);
    SQLException transactionFailure = new SQLException("Transaction failed");
    SQLException closeFailure = new SQLException("Connection close failed");
    Mockito.doThrow(closeFailure).when(connection).close();

    JdbcMetadataIntegrityTransaction.closeAfterFailedCleanup(connection, transactionFailure);

    Mockito.verify(connection).close();
    Assertions.assertArrayEquals(
        new Throwable[] {closeFailure}, transactionFailure.getSuppressed());
  }
}
