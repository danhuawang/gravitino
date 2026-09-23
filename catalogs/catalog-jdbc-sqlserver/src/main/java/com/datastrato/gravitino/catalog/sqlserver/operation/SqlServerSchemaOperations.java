/*
 * Copyright 2026 Datastrato Inc.
 */
package com.datastrato.gravitino.catalog.sqlserver.operation;

import com.datastrato.gravitino.catalog.sqlserver.utils.SqlServerUtils;
import com.google.common.collect.ImmutableSet;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.meta.AuditInfo;

/**
 * Schema operations for SQL Server catalog. Maps Gravitino Schema to SQL Server Schema (e.g., dbo).
 * Extends JdbcDatabaseOperations following the same pattern as PostgreSqlSchemaOperations.
 */
public class SqlServerSchemaOperations extends JdbcDatabaseOperations {

  private static final Set<String> SYSTEM_SCHEMAS =
      ImmutableSet.of(
          "guest",
          "information_schema",
          "sys",
          "db_owner",
          "db_accessadmin",
          "db_securityadmin",
          "db_ddladmin",
          "db_backupoperator",
          "db_datareader",
          "db_datawriter",
          "db_denydatareader",
          "db_denydatawriter");

  /** Schema row plus its {@code MS_Description} extended property (class 3 = SCHEMA). */
  private static final String LOAD_SCHEMA_SQL =
      "SELECT s.name, CAST(ep.value AS NVARCHAR(MAX)) FROM sys.schemas s "
          + "LEFT JOIN sys.extended_properties ep ON ep.class = 3 AND ep.major_id = s.schema_id "
          + "AND ep.minor_id = 0 AND ep.name = 'MS_Description' "
          + "WHERE s.name = ?";

  private String database;

  @Override
  public void initialize(
      DataSource dataSource, JdbcExceptionConverter exceptionMapper, Map<String, String> conf) {
    super.initialize(dataSource, exceptionMapper, conf);
    database = new JdbcConfig(conf).getJdbcDatabase();
  }

  /**
   * {@inheritDoc}
   *
   * <p>SQL Server has no {@code COMMENT ON SCHEMA}. A comment is stored as the schema's {@code
   * MS_Description} extended property, which is what SQL Server tools show as the description.
   * {@code CREATE SCHEMA} must be the only statement in its batch, so the property is added in a
   * second statement inside the same transaction.
   *
   * <p>The property holds the caller's text only: the Gravitino identifier that the JDBC layer
   * appends to the comment is stripped, so nothing Gravitino-specific reaches the source. Nothing
   * was written here before this catalog supported schema comments, so no identifier is lost;
   * {@code JdbcCatalogOperations.loadSchema} resolves the schema through the entity store instead.
   * Table comments in this catalog still carry the identifier, see issue #1750.
   *
   * <p>A schema comment can only be set at create time. {@code JdbcCatalogOperations.alterSchema}
   * is unsupported across every JDBC catalog, so there is no path that updates the property later.
   */
  @Override
  public void create(String schema, String comment, Map<String, String> properties)
      throws SchemaAlreadyExistsException {
    LOG.info("Beginning to create schema {}", schema);
    String createSql = generateCreateDatabaseSql(schema, comment, properties);
    String schemaComment = StringIdentifier.removeIdFromComment(comment);
    try (Connection connection = getConnection()) {
      if (StringUtils.isEmpty(schemaComment)) {
        JdbcConnectorUtils.executeUpdate(connection, createSql);
      } else {
        connection.setAutoCommit(false);
        try {
          JdbcConnectorUtils.executeUpdate(connection, createSql);
          JdbcConnectorUtils.executeUpdate(
              connection, SqlServerUtils.generateAddSchemaCommentSql(schema, schemaComment));
          connection.commit();
        } catch (SQLException | RuntimeException e) {
          try {
            connection.rollback();
          } catch (SQLException rollbackFailure) {
            e.addSuppressed(rollbackFailure);
          }
          throw e;
        }
      }
      LOG.info("Finished creating schema {}", schema);
    } catch (SQLException se) {
      throw toSchemaException(schema, exceptionMapper.toGravitinoException(se));
    }
  }

  @Override
  public List<String> listDatabases() {
    List<String> result = new ArrayList<>();
    try (Connection connection = getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT s.name FROM sys.schemas s ORDER BY s.name");
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        String schemaName = resultSet.getString(1);
        if (!isSystemDatabase(schemaName)) {
          result.add(schemaName);
        }
      }
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
    return result;
  }

  @Override
  public JdbcSchema load(String schema) throws NoSuchSchemaException {
    try (Connection connection = getConnection();
        PreparedStatement statement = connection.prepareStatement(LOAD_SCHEMA_SQL)) {
      statement.setString(1, schema);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new NoSuchSchemaException("No such schema: %s", schema);
        }
        String comment = resultSet.getString(2);
        return JdbcSchema.builder()
            .withName(schema)
            .withComment(StringUtils.isEmpty(comment) ? null : comment)
            .withAuditInfo(AuditInfo.EMPTY)
            .withProperties(Collections.emptyMap())
            .build();
      }
    } catch (NoSuchSchemaException e) {
      throw e;
    } catch (SQLException se) {
      throw exceptionMapper.toGravitinoException(se);
    }
  }

  @Override
  protected String generateCreateDatabaseSql(
      String schema, String comment, Map<String, String> properties) {
    if (MapUtils.isNotEmpty(properties)) {
      throw new UnsupportedOperationException(
          "SQL Server does not support properties on schema create.");
    }
    return "CREATE SCHEMA " + quoteIdentifier(schema) + " AUTHORIZATION [dbo]";
  }

  @Override
  protected String generateDropDatabaseSql(String schema, boolean cascade) {
    return "DROP SCHEMA " + quoteIdentifier(schema);
  }

  /** {@inheritDoc} */
  @Override
  protected void dropDatabase(String schema, boolean cascade) {
    try (Connection connection = getConnection()) {
      connection.setAutoCommit(false);
      try {
        if (cascade) {
          dropSchemaObjects(connection, schema);
        }
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate(generateDropDatabaseSql(schema, false));
        }
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          e.addSuppressed(rollbackFailure);
        }
        throw e;
      }
    } catch (SQLException e) {
      if (isDependencyError(e)) {
        throw new NonEmptySchemaException(e, "Cannot drop schema %s: %s", schema, e.getMessage());
      }
      throw exceptionMapper.toGravitinoException(e);
    }
  }

  @Override
  protected Connection getConnection() throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setCatalog(database);
    return connection;
  }

  /**
   * Checks if a schema exists in the current database.
   *
   * @param connection The JDBC connection
   * @param schema The schema name to check
   * @return true if the schema exists, false otherwise
   * @throws SQLException if a database access error occurs
   */
  public boolean schemaExists(Connection connection, String schema) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT s.name FROM sys.schemas s WHERE s.name = ?")) {
      statement.setString(1, schema);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          if (schema.equalsIgnoreCase(resultSet.getString(1))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  @Override
  protected boolean supportSchemaComment() {
    return true;
  }

  @Override
  protected Set<String> createSysDatabaseNameSet() {
    return SYSTEM_SCHEMAS;
  }

  private void dropSchemaObjects(Connection connection, String schema) throws SQLException {
    Map<Integer, String> objectDrops = new LinkedHashMap<>();
    // Child objects (indexes, triggers and constraints) are removed with their parent table.
    // Table types are enumerated through sys.types, not their backing sys.objects entries.
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT name, type, object_id FROM sys.objects WHERE schema_id = SCHEMA_ID(?) "
                + "AND parent_object_id = 0 AND is_ms_shipped = 0 AND type <> 'TT'")) {
      statement.setString(1, schema);
      try (ResultSet objects = statement.executeQuery()) {
        while (objects.next()) {
          String name = objects.getString(1);
          String type = objects.getString(2).trim();
          String kind;
          switch (type) {
            case "U":
              kind = "TABLE";
              break;
            case "V":
              kind = "VIEW";
              break;
            case "P":
            case "PC":
              kind = "PROCEDURE";
              break;
            case "FN":
            case "IF":
            case "TF":
            case "FS":
            case "FT":
              kind = "FUNCTION";
              break;
            case "SN":
              kind = "SYNONYM";
              break;
            case "SO":
              kind = "SEQUENCE";
              break;
            case "SQ":
              kind = "QUEUE";
              break;
            case "AF":
              kind = "AGGREGATE";
              break;
            case "R":
              kind = "RULE";
              break;
            case "D":
              kind = "DEFAULT";
              break;
            case "ET":
              kind = "EXTERNAL TABLE";
              break;
            case "SP":
              kind = "SECURITY POLICY";
              break;
            default:
              throw new UnsupportedOperationException(
                  "Cannot cascade drop schema "
                      + schema
                      + ": unsupported object "
                      + name
                      + " ("
                      + type
                      + ")");
          }
          objectDrops.put(
              objects.getInt(3),
              "DROP " + kind + " " + quoteIdentifier(schema) + "." + quoteIdentifier(name));
        }
      }
    }
    // Table types can depend on alias types, and routines can depend on table
    // types. Include their backing object IDs in the same graph as ordinary schema objects.
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT name, type_table_object_id FROM sys.table_types WHERE schema_id = SCHEMA_ID(?)")) {
      statement.setString(1, schema);
      try (ResultSet types = statement.executeQuery()) {
        while (types.next()) {
          objectDrops.put(
              types.getInt(2),
              "DROP TYPE " + quoteIdentifier(schema) + "." + quoteIdentifier(types.getString(1)));
        }
      }
    }
    List<String> drops = orderObjectDrops(connection, schema, objectDrops);
    collectTypeDrops(
        connection,
        schema,
        drops,
        "sys.types",
        "TYPE",
        " AND is_user_defined = 1 AND is_table_type = 0");
    collectTypeDrops(
        connection, schema, drops, "sys.xml_schema_collections", "XML SCHEMA COLLECTION", "");

    // Break FK cycles within this schema. References from other schemas remain intact and
    // cause the transaction to roll back rather than silently modifying another schema.
    List<String> constraints = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT OBJECT_NAME(parent_object_id), name FROM sys.foreign_keys "
                + "WHERE schema_id = SCHEMA_ID(?)")) {
      statement.setString(1, schema);
      try (ResultSet keys = statement.executeQuery()) {
        while (keys.next()) {
          constraints.add(
              "ALTER TABLE "
                  + quoteIdentifier(schema)
                  + "."
                  + quoteIdentifier(keys.getString(1))
                  + " DROP CONSTRAINT "
                  + quoteIdentifier(keys.getString(2)));
        }
      }
    }
    try (Statement statement = connection.createStatement()) {
      for (String constraint : constraints) {
        statement.executeUpdate(constraint);
      }
      for (String drop : drops) {
        statement.executeUpdate(drop);
      }
    }
  }

  private List<String> orderObjectDrops(
      Connection connection, String schema, Map<Integer, String> objects) throws SQLException {
    Map<Integer, Integer> dependentCounts = new HashMap<>();
    Map<Integer, List<Integer>> references = new HashMap<>();
    objects.keySet().forEach(id -> dependentCounts.put(id, 0));
    // Attribute CHECK/DEFAULT dependencies to their parent table, which removes the constraints.
    // Discover binding dependencies before executing DDL: SQL Server can abort the transaction
    // on a failed DROP, so retrying failed drops within that transaction is not safe.
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COALESCE(NULLIF(o.parent_object_id, 0), o.object_id), "
                + "CASE WHEN d.referenced_class = 6 THEN t.type_table_object_id "
                + "ELSE d.referenced_id END FROM sys.sql_expression_dependencies d "
                + "JOIN sys.objects o ON d.referencing_class = 1 AND o.object_id = d.referencing_id "
                + "LEFT JOIN sys.table_types t ON d.referenced_class = 6 "
                + "AND t.user_type_id = d.referenced_id "
                + "WHERE o.schema_id = SCHEMA_ID(?) "
                + "AND ((d.referenced_class = 1 "
                + "AND OBJECT_SCHEMA_NAME(d.referenced_id) = ? "
                + "AND (d.is_schema_bound_reference = 1 OR o.type IN ('C', 'D'))) "
                + "OR (d.referenced_class = 6 AND t.schema_id = SCHEMA_ID(?)))")) {
      statement.setString(1, schema);
      statement.setString(2, schema);
      statement.setString(3, schema);
      try (ResultSet dependencies = statement.executeQuery()) {
        while (dependencies.next()) {
          int referencing = dependencies.getInt(1);
          int referenced = dependencies.getInt(2);
          if (referencing != referenced
              && objects.containsKey(referencing)
              && objects.containsKey(referenced)) {
            references.computeIfAbsent(referencing, unused -> new ArrayList<>()).add(referenced);
            dependentCounts.merge(referenced, 1, Integer::sum);
          }
        }
      }
    }
    Deque<Integer> ready = new ArrayDeque<>();
    objects.keySet().stream().filter(id -> dependentCounts.get(id) == 0).forEach(ready::add);
    List<String> drops = new ArrayList<>();
    while (!ready.isEmpty()) {
      int id = ready.remove();
      drops.add(objects.get(id));
      for (int referenced : references.getOrDefault(id, Collections.emptyList())) {
        if (dependentCounts.merge(referenced, -1, Integer::sum) == 0) {
          ready.add(referenced);
        }
      }
    }
    if (drops.size() != objects.size()) {
      throw new NonEmptySchemaException(
          "Cannot drop schema %s: cyclic schema-bound dependencies", schema);
    }
    return drops;
  }

  private void collectTypeDrops(
      Connection connection,
      String schema,
      List<String> drops,
      String catalog,
      String kind,
      String filter)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT name FROM " + catalog + " WHERE schema_id = SCHEMA_ID(?)" + filter)) {
      statement.setString(1, schema);
      try (ResultSet types = statement.executeQuery()) {
        while (types.next()) {
          drops.add(
              "DROP "
                  + kind
                  + " "
                  + quoteIdentifier(schema)
                  + "."
                  + quoteIdentifier(types.getString(1)));
        }
      }
    }
  }

  /**
   * SQL Server returns error code 2714 for both duplicate tables and duplicate schemas. The
   * converter maps 2714 to TableAlreadyExistsException, but in schema context we need
   * SchemaAlreadyExistsException.
   */
  private static RuntimeException toSchemaException(String schema, RuntimeException e) {
    if (e instanceof TableAlreadyExistsException) {
      return new SchemaAlreadyExistsException(e, "Schema already exists: %s", schema);
    }
    return e;
  }

  private static boolean isDependencyError(SQLException e) {
    return e.getErrorCode() == 3729 || e.getErrorCode() == 3726 || e.getErrorCode() == 3732;
  }

  private static String quoteIdentifier(String identifier) {
    return "[" + identifier.replace("]", "]]") + "]";
  }
}
