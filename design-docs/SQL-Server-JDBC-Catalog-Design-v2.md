<!--
  Copyright 2026 Datastrato Inc.
-->

# SQL Server JDBC Catalog Design

## 1. Background and Goals

### 1.1 Background

Apache Gravitino already supports MySQL, PostgreSQL, Doris, StarRocks, and ClickHouse as JDBC catalogs. Microsoft SQL Server is widely used in enterprise environments for transactional and analytical workloads. Adding SQL Server support expands Gravitino's reach to enterprise users who rely on SQL Server.

### 1.2 Supported Versions

The primary supported version range is **SQL Server 2016–2022**, tested against actual version images. SQL Server 2016 is the minimum primary target because it is the earliest version still under Microsoft Extended Support and introduced key improvements such as `DROP IF EXISTS`, temporal tables, and JSON support.

Additionally, the connector maintains backward compatibility with **SQL Server 2008** to support existing enterprise customers running legacy environments. The implementation deliberately uses only T-SQL syntax and system views available since SQL Server 2008 (e.g., `sys.schemas`, `sys.columns`, `sys.types`, `sp_addextendedproperty`, `IDENTITY`), so the same codebase works across all versions from 2008 to 2022 without version-specific branching. A dedicated compatibility test will be run against SQL Server 2008 (via a 2017 container with compatibility level 100) to verify this.

Integration tests will use:
- `mcr.microsoft.com/mssql/server:2022-latest` (primary)
- `mcr.microsoft.com/mssql/server:2019-latest` (secondary)
- `mcr.microsoft.com/mssql/server:2017-latest` (legacy validation, also used for 2008 compatibility level testing)

### 1.3 Goals

- Implement SQL Server as a Gravitino JDBC catalog following the existing framework (`JdbcCatalog`, `JdbcCatalogOperations`, `JdbcDatabaseOperations`, `JdbcTableOperations`).
- Support schema and table CRUD operations with the correct hierarchy mapping: **Gravitino Catalog → SQL Server Database**, **Gravitino Schema → SQL Server Schema** (e.g., `dbo`).
- Provide two-way type conversion between Gravitino and SQL Server, with strict one-to-one mappings (`nvarchar` ↔ `StringType`, `varchar(n)` ↔ `VarCharType(n)`). Types without a direct Gravitino equivalent are mapped to `ExternalType`.
- Handle SQL Server specific features: `IDENTITY`, extended properties for comments, index support.
- Bundle the Microsoft JDBC driver as a Maven dependency (MIT-licensed, ASF Category A compatible).
- Clearly document unsupported features and limitations.

### 1.4 Non-Goals (v1)

- View support: Gravitino's `ViewCatalog` interface is marked `@Unstable` and only provides `loadView()` / `viewExists()`. No JDBC catalog currently implements `ViewCatalog`. Full view CRUD is not yet available in the framework. View support will be added when the Gravitino framework matures.
- Partitioned table creation through Gravitino.
- Windows Authentication / Entra ID / Azure Managed Identity (v2 candidates).
- `datetimeoffset` native mapping (use `ExternalType`).

## 2. Architecture and Hierarchy Mapping

SQL Server has a three-level hierarchy: **Instance → Database → Schema → Objects**. This is similar to PostgreSQL and differs from MySQL.

| Layer         | Gravitino | SQL Server           | Notes                                                  |
|---------------|-----------|----------------------|--------------------------------------------------------|
| Top container | `Catalog` | Database             | One Gravitino catalog maps to one SQL Server database. |
| Namespace     | `Schema`  | Schema (e.g., `dbo`) | Gravitino schema maps to a SQL Server schema.          |
| Data object   | `Table`   | Table                | Tables are created under a schema.                     |

This mapping follows the same pattern as the PostgreSQL JDBC catalog in Gravitino, where `PostgreSqlSchemaOperations` extends `JdbcDatabaseOperations` to manage PostgreSQL schemas (not databases). The SQL Server connector will follow the same approach.

**Comparison with existing catalogs**:

| Topic                    | MySQL                                 | PostgreSQL                               | SQL Server                               |
|--------------------------|---------------------------------------|------------------------------------------|------------------------------------------|
| Schema concept           | `DATABASE` = schema                   | `SCHEMA` inside a database               | `SCHEMA` inside a database               |
| Gravitino mapping        | Catalog → Instance, Schema → Database | Catalog → Database, Schema → Schema      | Catalog → Database, Schema → Schema      |
| Base class               | `JdbcDatabaseOperations`              | `JdbcDatabaseOperations` (as schema ops) | `JdbcDatabaseOperations` (as schema ops) |
| `jdbc-database` required | No                                    | Yes                                      | Yes                                      |

## 3. Module Layout

The module follows the same structure as existing JDBC catalogs (PostgreSQL, StarRocks):

```
catalogs/catalog-jdbc-sqlserver/
├── build.gradle.kts
├── src/main/java/com/datastrato/gravitino/catalog/sqlserver/
│   ├── SqlServerCatalog.java                          // extends JdbcCatalog
│   ├── SqlServerCatalogCapability.java                // implements Capability
│   ├── SqlServerCatalogOperations.java                // extends JdbcCatalogOperations
│   ├── converter/
│   │   ├── SqlServerTypeConverter.java                // extends JdbcTypeConverter
│   │   ├── SqlServerColumnDefaultValueConverter.java  // extends JdbcColumnDefaultValueConverter
│   │   └── SqlServerExceptionConverter.java           // extends JdbcExceptionConverter
│   ├── operation/
│   │   ├── SqlServerSchemaOperations.java             // extends JdbcDatabaseOperations
│   │   └── SqlServerTableOperations.java              // extends JdbcTableOperations
│   └── utils/
│       └── SqlServerUtils.java                        // repeated method extraction
├── src/main/resources/
│   ├── META-INF/services/org.apache.gravitino.CatalogProvider
│   └── jdbc-sqlserver.conf
└── src/test/java/com/datastrato/gravitino/catalog/sqlserver/
    ├── converter/
    │   ├── TestSqlServerTypeConverter.java
    │   ├── TestSqlServerColumnDefaultValueConverter.java
    │   └── TestSqlServerExceptionConverter.java
    ├── operation/
    │   ├── TestSqlServerSchemaOperations.java
    │   └── TestSqlServerTableOperations.java
    └── integration/test/
        └── CatalogSqlServerIT.java                    // @Tag("gravitino-docker-test")
```

### 3.1 Key Classes and Their Roles

| Class                                  | Base Class                        | Responsibility                                                                                                                                              |
|----------------------------------------|-----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SqlServerCatalog`                     | `JdbcCatalog`                     | Entry point. `shortName()` returns `"jdbc-sqlserver"`. Creates all sub-components.                                                                          |
| `SqlServerCatalogOperations`           | `JdbcCatalogOperations`           | Overrides `getDriver()` to return the SQL Server JDBC driver.                                                                                               |
| `SqlServerCatalogCapability`           | implements `Capability`           | Name validation (128 char limit, SQL Server identifier rules), reserved schema names.                                                                       |
| `SqlServerSchemaOperations`            | `JdbcDatabaseOperations`          | Schema CRUD. Overrides `generateCreateDatabaseSql`, `generateDropDatabaseSql`, `listDatabases`, `load`, `supportSchemaComment`, `createSysDatabaseNameSet`. |
| `SqlServerTableOperations`             | `JdbcTableOperations`             | Table CRUD. Overrides `generateCreateTableSql`, `generateAlterTableSql`, `generateRenameTableSql`, `generatePurgeTableSql`, index support.                  |
| `SqlServerTypeConverter`               | `JdbcTypeConverter`               | Two-way type mapping between SQL Server and Gravitino types.                                                                                                |
| `SqlServerColumnDefaultValueConverter` | `JdbcColumnDefaultValueConverter` | Parses SQL Server default value expressions (strips parentheses, handles `GETDATE()`, `NEWID()`, etc.).                                                     |
| `SqlServerExceptionConverter`          | `JdbcExceptionConverter`          | Maps SQL Server error codes / SQLSTATEs to Gravitino exceptions.                                                                                            |
| `SqlServerUtils`                       | —                                 | Shared helpers: extended property SQL generation, default value parentheses stripping, nvarchar length conversion, etc.                                     |

### 3.2 SPI Registration

`META-INF/services/org.apache.gravitino.CatalogProvider`:
```
com.datastrato.gravitino.catalog.sqlserver.SqlServerCatalog
```

## 4. Catalog Properties and Authentication

### 4.1 Catalog Properties

Following the pattern of the PostgreSQL catalog, the SQL Server catalog requires `jdbc-database` in addition to the standard JDBC properties. Any property not defined by Gravitino can be passed to the SQL Server data source by adding the `gravitino.bypass.` prefix.

| Configuration item      | Description                                                                                                                                          | Default value  | Required  | Since Version |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|----------------|-----------|---------------|
| `jdbc-url`              | JDBC URL for connecting to the database. For example, `jdbc:sqlserver://localhost:1433;databaseName=mydb;encrypt=true;trustServerCertificate=false`. | (none)         | Yes       | 1.3.0         |
| `jdbc-driver`           | The driver of the JDBC connection: `com.microsoft.sqlserver.jdbc.SQLServerDriver`.                                                                   | (none)         | Yes       | 1.3.0         |
| `jdbc-database`         | The database name. Must be consistent with `databaseName` in `jdbc-url`.                                                                             | (none)         | Yes       | 1.3.0         |
| `jdbc-user`             | The JDBC user name.                                                                                                                                  | (none)         | Yes       | 1.3.0         |
| `jdbc-password`         | The JDBC password.                                                                                                                                   | (none)         | Yes       | 1.3.0         |
| `jdbc.pool.min-size`    | Minimum number of connections in the pool.                                                                                                           | `2`            | No        | 1.3.0         |
| `jdbc.pool.max-size`    | Maximum number of connections in the pool.                                                                                                           | `10`           | No        | 1.3.0         |
| `jdbc.pool.max-wait-ms` | Maximum wait time (ms) for a connection from the pool.                                                                                               | `30000`        | No        | 1.3.0         |

> **Note**: The `Since Version` will be aligned with the actual Gravitino release that includes this connector. The placeholder `1.3.0` should be updated during implementation.

### 4.2 JDBC URL and TLS

All documentation examples use TLS-enabled connections by default:

```
# Production (recommended)
jdbc:sqlserver://host:1433;databaseName=mydb;encrypt=true;trustServerCertificate=false

# Local development / testing only
jdbc:sqlserver://localhost:1433;databaseName=mydb;encrypt=true;trustServerCertificate=true
```

The `encrypt=true` parameter is the default in MSSQL JDBC Driver 10.x+. Documentation will prominently note TLS requirements.

> **Note for SQL Server 2008**: SQL Server 2008 only supports TLS 1.0, while modern MSSQL JDBC drivers (10.x+) require TLS 1.2 by default. Customers on SQL Server 2008 may need to use `encrypt=false` or configure TLS certificates on the server side.

### 4.3 Authentication

**v1 scope**: Username/password authentication only, consistent with all existing JDBC catalogs.

**Future candidates (v2)**:
- Microsoft Entra ID (formerly Azure AD) with client secret
- Azure Managed Identity
- Kerberos / Windows Integrated Authentication

These will be called out in user-facing documentation so customers can set expectations early.

### 4.4 JDBC Driver Dependency

The Microsoft JDBC Driver for SQL Server is [MIT-licensed](https://github.com/microsoft/mssql-jdbc/blob/main/LICENSE) and is ASF Category A compatible. It will be included as a standard Maven dependency in `build.gradle.kts`:

```kotlin
dependencies {
  implementation("com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11")
  // ...
}
```

Users do **not** need to manually download the driver. This differs from the PostgreSQL and MySQL catalogs which require manual driver download due to their respective license terms.

## 5. Schema Operations

### 5.1 Schema = SQL Server Schema

Within a catalog (database), a Gravitino schema maps to a SQL Server schema. The implementation class `SqlServerSchemaOperations` extends `JdbcDatabaseOperations`, following the same pattern as `PostgreSqlSchemaOperations`.

### 5.2 Schema Comment Handling

SQL Server does not natively support comments on schemas (no `COMMENT ON SCHEMA` equivalent, and extended properties on schemas are limited).

**Current framework behavior**: The `JdbcDatabaseOperations.create()` method checks `supportSchemaComment()`. If it returns `false` and the user provides a non-empty comment (after stripping the Gravitino internal identifier), it throws `UnsupportedOperationException`.

**Decision**: `supportSchemaComment()` returns `false` for SQL Server. This is consistent with MySQL, StarRocks, and OceanBase catalogs in Gravitino. The Gravitino internal identifier (embedded in the comment field by `StringIdentifier`) will still be stored because the framework only checks the user-visible portion of the comment.

> **Note on PM feedback**: The PM review suggested storing schema comments in Gravitino's metastore. However, the current JDBC catalog framework (`JdbcDatabaseOperations` / `JdbcCatalogOperations`) does not have a Gravitino-side persistence mechanism for schema comments — all existing JDBC catalogs either store comments in the external database (PostgreSQL, Doris, ClickHouse) or reject them (MySQL, StarRocks). Implementing Gravitino-side comment storage would require a framework-level change across all JDBC connectors, which is out of scope for this connector. This gap should be tracked as a separate framework enhancement issue.

### 5.3 System Schemas Filter List

```java
private static final Set<String> SYSTEM_SCHEMAS = ImmutableSet.of(
    "guest", "INFORMATION_SCHEMA", "sys",
    "db_owner", "db_accessadmin", "db_securityadmin", "db_ddladmin",
    "db_backupoperator", "db_datareader", "db_datawriter",
    "db_denydatareader", "db_denydatawriter"
);
```

> **Note**: `dbo` is NOT filtered out. It is the default schema and users should be able to see and use it. This differs from the original design which incorrectly filtered `dbo`.

### 5.4 Schema Operations SQL

**List schemas**:
```sql
SELECT s.name FROM sys.schemas s
WHERE s.name NOT IN ('guest','INFORMATION_SCHEMA','sys','db_owner',...)
  AND s.principal_id != 0
ORDER BY s.name
```

Implementation: Override `listDatabases()` in `SqlServerSchemaOperations` to query `sys.schemas` (similar to how PostgreSQL overrides to query `pg_namespace`).

**Create schema**:
```sql
CREATE SCHEMA [schema_name] AUTHORIZATION [dbo]
```

Implementation: Override `generateCreateDatabaseSql()`.

**Drop schema**:
```sql
DROP SCHEMA [schema_name]
```

SQL Server does not support `DROP SCHEMA ... CASCADE`. If the schema contains any objects, `DROP SCHEMA` will fail and SQL Server's native error is surfaced directly to the user.

**Load schema**:
```sql
SELECT s.name FROM sys.schemas s WHERE s.name = ?
```

Returns `JdbcSchema` with empty comment (since schema comments are not supported).

### 5.5 Connection Handling

Override `getConnection()` to set the catalog (database) context:

```java
@Override
protected Connection getConnection() throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setCatalog(database);
    return connection;
}
```

This follows the same pattern as `PostgreSqlSchemaOperations.getConnection()`.

## 6. Table Operations

### 6.1 Table = SQL Server Table Under a Schema

Tables are created as `[schema].[table]`. The `SqlServerTableOperations` extends `JdbcTableOperations` and overrides the SQL generation methods.

### 6.2 Identifier Quoting

SQL Server uses square brackets `[]` for quoting identifiers:

```java
@Override
protected String quoteIdentifier(String identifier) {
    return "[" + identifier + "]";
}
```

### 6.3 Table and Column Comments (Extended Properties)

SQL Server stores comments as extended properties using `sp_addextendedproperty` / `sp_updateextendedproperty` / `sp_dropextendedproperty`.

**Add table comment**:
```sql
EXEC sp_addextendedproperty
    @name = N'MS_Description', @value = N'comment',
    @level0type = N'SCHEMA', @level0name = N'schema_name',
    @level1type = N'TABLE', @level1name = N'table_name'
```

**Add column comment**:
```sql
EXEC sp_addextendedproperty
    @name = N'MS_Description', @value = N'comment',
    @level0type = N'SCHEMA', @level0name = N'schema_name',
    @level1type = N'TABLE', @level1name = N'table_name',
    @level2type = N'COLUMN', @level2name = N'column_name'
```

**Update comment**: Use `sp_updateextendedproperty` with the same parameters.

**Implementation**: In `createTable`, after executing `CREATE TABLE`, execute `sp_addextendedproperty` for the table comment and each column that has a comment. For `alterTable`, use `sp_updateextendedproperty` or drop-and-add pattern.

> **Note**: Gravitino embeds internal metadata (StringIdentifier) in comments. The extended property value limit is 7,500 characters (SQL Server 2016+), which is sufficient.

### 6.4 Retrieve Column Metadata

Override `getBasicJdbcColumnInfo()` and `correctJdbcTableFields()` to use system views for richer metadata:

```sql
SELECT
    c.name AS COLUMN_NAME,
    t.name AS TYPE_NAME,
    c.max_length,
    c.precision,
    c.scale,
    c.is_nullable,
    c.is_identity,
    dc.definition AS COLUMN_DEF,
    ep.value AS REMARKS
FROM sys.columns c
JOIN sys.types t ON c.user_type_id = t.user_type_id
LEFT JOIN sys.default_constraints dc
    ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id
LEFT JOIN sys.extended_properties ep
    ON ep.major_id = c.object_id AND ep.minor_id = c.column_id
    AND ep.name = 'MS_Description'
WHERE c.object_id = OBJECT_ID(? + '.' + ?)
ORDER BY c.column_id
```

### 6.5 CREATE TABLE SQL

```sql
CREATE TABLE [schema_name].[table_name] (
    [col1] int NOT NULL IDENTITY(1,1),
    [col2] varchar(100) NOT NULL,
    [col3] datetime2(3) DEFAULT GETDATE(),
    [col4] nvarchar(500) NULL,
    CONSTRAINT [PK_table_name] PRIMARY KEY ([col1])
)
```

Key points:
- Use `[]` for quoting identifiers.
- `IDENTITY(1,1)` for auto-increment columns.
- Primary key defined as a named constraint.
- Comments are added separately via `sp_addextendedproperty`.

### 6.6 ALTER TABLE Operations

| Operation                      | SQL                                                                      |
|--------------------------------|--------------------------------------------------------------------------|
| Add column                     | `ALTER TABLE [s].[t] ADD [col] type [NOT NULL] [DEFAULT ...]`            |
| Drop column                    | `ALTER TABLE [s].[t] DROP COLUMN [col]`                                  |
| Modify column type/nullability | `ALTER TABLE [s].[t] ALTER COLUMN [col] type [NOT NULL\|NULL]`           |
| Rename column                  | `EXEC sp_rename '[s].[t].[old]', 'new', 'COLUMN'`                        |
| Rename table                   | `EXEC sp_rename '[s].[old]', 'new'`                                      |
| Add primary key                | `ALTER TABLE [s].[t] ADD CONSTRAINT [pk] PRIMARY KEY (col)`              |
| Drop constraint                | `ALTER TABLE [s].[t] DROP CONSTRAINT [name]`                             |
| Update table comment           | `EXEC sp_updateextendedproperty ...` or drop + add                       |
| Update column comment          | `EXEC sp_updateextendedproperty ...` or drop + add                       |
| Update column default value    | Drop existing default constraint, then `ALTER TABLE ... ADD DEFAULT ...` |

**Supported alter operations** (consistent with PostgreSQL catalog):
- `RenameTable`
- `UpdateComment`
- `AddColumn`
- `DeleteColumn`
- `RenameColumn`
- `UpdateColumnType`
- `UpdateColumnNullability`
- `UpdateColumnComment`
- `UpdateColumnDefaultValue`

**Limitations**:
- `UpdateColumnPosition` is not supported (same as PostgreSQL).
- `ALTER COLUMN` cannot add or remove `IDENTITY`. SQL Server's native error is surfaced directly to the user.
- Renaming a table cannot be submitted in the same batch as other alter operations.

### 6.7 Table Indexes

Support `PRIMARY_KEY` and `UNIQUE_KEY` constraints (P1 priority, consistent with PostgreSQL catalog).

- `PRIMARY_KEY` → `CONSTRAINT [name] PRIMARY KEY ([col1], [col2])`
- `UNIQUE_KEY` → `CONSTRAINT [name] UNIQUE ([col1], [col2])`

Regular non-unique indexes are not exposed in v1.

## 7. Type Conversion

### 7.1 Design Principles

1. Map to the closest Gravitino type when an exact one-to-one mapping exists. Every supported mapping must be strictly bidirectional (one Gravitino type ↔ one SQL Server type) to ensure deterministic round-trip behavior.
2. `nvarchar` ↔ `StringType` as a one-to-one pair (per PM review feedback, to keep `nvarchar` usable in Gravitino for federation and predicate pushdown).
3. For types with no direct one-to-one Gravitino equivalent, use `ExternalType` to preserve the original type name. This includes legacy/deprecated types (`datetime`, `smalldatetime`, `text`, `ntext`, `image`, `money`, `smallmoney`) and types that would create multi-to-one ambiguity (`numeric`, `nchar`, `varchar(max)`).
4. Reject with `IllegalArgumentException` if a Gravitino type cannot be mapped to SQL Server.

### 7.2 SQL Server ↔ Gravitino Type Mapping

All type mappings are strictly one-to-one to ensure deterministic round-trip behavior. This is consistent with how MySQL and PostgreSQL catalogs define their type mappings.

#### Supported One-to-One Mappings

| Gravitino Type                     | SQL Server Type    | Notes                                 |
|------------------------------------|--------------------|---------------------------------------|
| `ByteType`                         | `tinyint`          | SQL Server tinyint is unsigned 0-255. |
| `ShortType`                        | `smallint`         |                                       |
| `IntegerType`                      | `int`              |                                       |
| `LongType`                         | `bigint`           |                                       |
| `BooleanType`                      | `bit`              |                                       |
| `DecimalType(p,s)`                 | `decimal(p,s)`     | p ≤ 38.                               |
| `FloatType`                        | `real`             | 32-bit IEEE 754.                      |
| `DoubleType`                       | `float`            | 64-bit IEEE 754 (`float(53)`).        |
| `DateType`                         | `date`             |                                       |
| `TimeType(p)`                      | `time(p)`          | Precision p (0–7).                    |
| `TimestampType.withoutTimeZone(p)` | `datetime2(p)`     | p (0–7).                              |
| `FixedCharType(n)`                 | `char(n)`          |                                       |
| `VarCharType(n)`                   | `varchar(n)`       | n ≤ 8000.                             |
| `StringType`                       | `nvarchar`         | See rationale below.                  |
| `FixedType(n)`                     | `binary(n)`        |                                       |
| `BinaryType`                       | `varbinary(max)`   |                                       |
| `UUIDType`                         | `uniqueidentifier` |                                       |

#### Types Mapped to ExternalType

SQL Server types not in the one-to-one table above are mapped to `ExternalType` on read. Users can use `ExternalType("typename")` when creating tables through Gravitino, and the converter emits the type name as-is.

| SQL Server Type            | Gravitino Type                      |
|----------------------------|-------------------------------------|
| `numeric(p,s)`             | `ExternalType("numeric(p,s)")`      |
| `datetime`                 | `ExternalType("datetime")`          |
| `smalldatetime`            | `ExternalType("smalldatetime")`     |
| `datetimeoffset(p)`        | `ExternalType("datetimeoffset(p)")` |
| `nchar(n)`                 | `ExternalType("nchar(n)")`          |
| `varchar(max)`             | `ExternalType("varchar(max)")`      |
| `text`                     | `ExternalType("text")`              |
| `ntext`                    | `ExternalType("ntext")`             |
| `image`                    | `ExternalType("image")`             |
| `money`                    | `ExternalType("money")`             |
| `smallmoney`               | `ExternalType("smallmoney")`        |
| `xml`                      | `ExternalType("xml")`               |
| `geography`                | `ExternalType("geography")`         |
| `geometry`                 | `ExternalType("geometry")`          |
| `sql_variant`              | `ExternalType("sql_variant")`       |
| `timestamp` / `rowversion` | `ExternalType("rowversion")`        |

> **Note**: `TimestampType.withTimeZone` is not supported. Use `ExternalType("datetimeoffset(p)")` instead.

#### nvarchar ↔ StringType Rationale

`nvarchar` is the default string type in virtually every modern SQL Server deployment. Mapping it to `ExternalType` would make these columns completely opaque to Gravitino — unable to be federated, filtered, or translated to other engines, which would be a blocker for customer POCs (per PM review feedback).

**Chosen approach**: Map `nvarchar` ↔ `StringType` as a strict one-to-one pair. `varchar(n)` ↔ `VarCharType(n)` is a separate one-to-one pair. This avoids multi-to-one ambiguity while keeping `nvarchar` usable in Gravitino.

This is consistent with industry practice: Trino/Starburst maps `nvarchar` → `VARCHAR` with full predicate pushdown support, and Dremio maps `nvarchar` → `varchar`.

## 8. Column Default Values

### 8.1 SQL Server Default Value Format

SQL Server wraps default values in parentheses in `sys.default_constraints.definition`. Examples:
- `((0))` → numeric literal `0`
- `('hello')` → string literal `'hello'`
- `(getdate())` → function `GETDATE()`
- `(newid())` → function `NEWID()`
- `(NULL)` → NULL

### 8.2 toGravitino Logic

1. Strip outer parentheses (maybe nested, e.g., `((0))` → `0`).
2. If the value matches `CURRENT_TIMESTAMP` or `getdate()` (case-insensitive), return `DEFAULT_VALUE_OF_CURRENT_TIMESTAMP`.
3. If the value is `NULL`, return `Literals.NULL`.
4. Parse based on column type:
   - Numeric types → appropriate numeric literal
   - String types → strip surrounding quotes, return `Literals.varcharLiteral` or `Literals.stringLiteral`
   - Date/time types → parse and return appropriate literal
   - Other → return `UnparsedExpression`

### 8.3 fromGravitino Logic

Convert Gravitino expressions to SQL Server syntax:
- `CURRENT_TIMESTAMP` → `GETDATE()`
- `Literals.NULL` → `NULL`
- Numeric literals → value as-is
- String literals → `'value'`
- Other function expressions → `(expression)`

## 9. IDENTITY (Auto-Increment)

### 9.1 Create with IDENTITY

When `autoIncrement = true` on a column:
- Generate `IDENTITY(1,1)` in the column definition.
- The column must be `NOT NULL` (enforced by SQL Server).
- Only one `IDENTITY` column per table (enforced by SQL Server).

### 9.2 Load IDENTITY Info

When loading table metadata, check `sys.columns.is_identity = 1` to set `autoIncrement = true` on the column.

### 9.3 Limitations

- SQL Server does not support adding or removing `IDENTITY` via `ALTER COLUMN`. If attempted, SQL Server's native error is surfaced directly to the user.
- Seed and increment are always `(1,1)` in v1. Custom seed/increment may be added in a future version.

## 10. Exception Mapping

`SqlServerExceptionConverter` maps SQL Server error codes to Gravitino exceptions. SQL Server uses numeric error codes (not SQLSTATE classes like PostgreSQL).

| Error Code | Description                 | Gravitino Exception                                                                 |
|------------|-----------------------------|-------------------------------------------------------------------------------------|
| 2714       | Object already exists       | `SchemaAlreadyExistsException` or `TableAlreadyExistsException` (context-dependent) |
| 15032      | Schema already exists       | `SchemaAlreadyExistsException`                                                      |
| 208        | Invalid object name         | `NoSuchTableException`                                                              |
| 3701       | Cannot drop, does not exist | `NoSuchTableException` or `NoSuchSchemaException`                                   |
| 15151      | Cannot find schema          | `NoSuchSchemaException`                                                             |
| 229        | Permission denied           | `ConnectionFailedException`                                                         |
| 4060       | Cannot open database        | `NoSuchSchemaException`                                                             |
| 18456      | Login failed                | `ConnectionFailedException`                                                         |
| Other      | —                           | `GravitinoRuntimeException`                                                         |

Implementation pattern follows `PostgreSqlExceptionConverter`, using `sqlException.getErrorCode()` for dispatch:

```java
@Override
public GravitinoRuntimeException toGravitinoException(SQLException se) {
    switch (se.getErrorCode()) {
        case 2714:
        case 15032:
            // Distinguish schema vs table based on message content
            if (se.getMessage() != null && se.getMessage().contains("schema")) {
                return new SchemaAlreadyExistsException(se.getMessage(), se);
            }
            return new TableAlreadyExistsException(se.getMessage(), se);
        case 208:
            return new NoSuchTableException(se.getMessage(), se);
        case 3701:
        case 15151:
            return new NoSuchSchemaException(se.getMessage(), se);
        case 229:
        case 18456:
        case 4060:
            return new ConnectionFailedException(se.getMessage(), se);
        default:
            return new GravitinoRuntimeException(se.getMessage(), se);
    }
}
```

## 11. Catalog Capability

`SqlServerCatalogCapability` implements the `Capability` interface to define SQL Server-specific naming rules.

```java
public class SqlServerCatalogCapability implements Capability {

    // SQL Server identifiers: up to 128 characters, start with letter/underscore/@/#,
    // contain letters, digits, underscores, @, #, $
    public static final String SQLSERVER_NAME_PATTERN = "^[\\w\\p{L}@#$][\\w\\p{L}@#$]{0,127}$";

    private static final Set<String> RESERVED_SCHEMAS = Set.of(
        "guest", "information_schema", "sys",
        "db_owner", "db_accessadmin", "db_securityadmin", "db_ddladmin",
        "db_backupoperator", "db_datareader", "db_datawriter",
        "db_denydatareader", "db_denydatawriter"
    );

    @Override
    public CapabilityResult specificationOnName(Scope scope, String name) {
        if (!name.matches(SQLSERVER_NAME_PATTERN)) {
            return CapabilityResult.unsupported(
                String.format("The %s name '%s' is illegal.", scope, name));
        }
        if (scope == Scope.SCHEMA && RESERVED_SCHEMAS.contains(name.toLowerCase())) {
            return CapabilityResult.unsupported(
                String.format("The %s name '%s' is reserved.", scope, name));
        }
        return CapabilityResult.SUPPORTED;
    }

    @Override
    public CapabilityResult caseSensitiveOnName(Scope scope) {
        // SQL Server is case-insensitive by default (depends on collation)
        return CapabilityResult.unsupported(
            "SQL Server is case-insensitive by default.");
    }
}
```

## 12. View Support

### 12.1 Current Gravitino Framework Status

The `ViewCatalog` interface exists in Gravitino (`api/src/main/java/org/apache/gravitino/rel/ViewCatalog.java`) but is marked `@Unstable` and only provides two methods:
- `loadView(NameIdentifier ident)`
- `viewExists(NameIdentifier ident)` (default implementation)

Full view CRUD operations (create, list, alter, drop) are not yet defined in the interface. Currently, only the Iceberg catalog implements `ViewCatalog`, and it does so through Iceberg's own view API, not through the JDBC framework.

No JDBC catalog (`JdbcCatalogOperations`) implements `ViewCatalog`. The `JdbcTableOperations` interface has no view-related methods.

### 12.2 Decision

View support is **not included in v1** of the SQL Server connector because:
1. The Gravitino `ViewCatalog` interface lacks full CRUD operations.
2. No JDBC catalog currently implements view support.
3. Adding view support would require framework-level changes to `JdbcCatalogOperations` and `JdbcTableOperations`.

When the Gravitino framework adds full view support to the JDBC common module, the SQL Server connector will implement it. SQL Server views are straightforward to support via `sys.views` and `INFORMATION_SCHEMA.VIEWS`.

## 13. Other Considerations

### 13.1 Empty Strings
SQL Server treats `''` as distinct from `NULL`. No special handling needed.

### 13.2 Case Sensitivity
SQL Server is case-insensitive by default (depends on database collation). Identifiers are stored as created. Gravitino will return them as-is.

### 13.3 Identifier Length
Maximum 128 characters for SQL Server identifiers (enforced in `SqlServerCatalogCapability`).

### 13.4 Recycle Bin
Not present in SQL Server. `DROP TABLE` is immediate.

### 13.5 Synonyms
Not exposed. Only base tables are listed.

### 13.6 Gravitino Metadata in Comments
Gravitino embeds internal metadata (StringIdentifier) in table and column comments. For SQL Server, this is stored in extended properties (`MS_Description`). The connector must handle reading and writing these correctly, stripping the Gravitino identifier when presenting to users and preserving it when writing back.

## 14. Unsupported Features in v1

| Feature                                    | Reason                                                                                                                                                                                                                           |
|--------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| View support                               | Gravitino `ViewCatalog` interface is `@Unstable`, no JDBC catalog implements it yet. Framework-level work needed.                                                                                                                |
| Schema comments                            | SQL Server has no native schema comment mechanism. Framework lacks Gravitino-side persistence for JDBC catalog schema comments.                                                                                                  |
| Dropping non-empty schema with cascade     | SQL Server does not support `CASCADE`. Native error surfaced directly to the user.                                                                                                                                               |
| `datetimeoffset` native mapping            | No Gravitino equivalent. Use `ExternalType`.                                                                                                                                                                                     |
| `TimestampType.withTimeZone`               | SQL Server uses `datetimeoffset` which is offset-based, not timezone-based. Use `ExternalType`.                                                                                                                                  |
| Partitioned table creation                 | SQL Server partitioning is a three-step process: create a PARTITION FUNCTION, create a PARTITION SCHEME, then reference the scheme in CREATE TABLE via ON clause. This doesn't fit Gravitino's single-statement Transform model. |
| Indexes other than PRIMARY/UNIQUE          | Not exposed in v1.                                                                                                                                                                                                               |
| Auto-increment seed/increment              | Always `(1,1)` in v1.                                                                                                                                                                                                            |
| ALTER TABLE for IDENTITY columns           | SQL Server limitation: cannot add/remove IDENTITY via ALTER.                                                                                                                                                                     |
| Windows Auth / Entra ID / Managed Identity | v2 candidates.                                                                                                                                                                                                                   |
| `UpdateColumnPosition`                     | SQL Server does not support column reordering (same as PostgreSQL).                                                                                                                                                              |

## 15. Integration Testing

### 15.1 Docker Image

Primary: `mcr.microsoft.com/mssql/server:2022-latest`
Secondary: `mcr.microsoft.com/mssql/server:2019-latest`

Environment variables:
```
ACCEPT_EULA=Y
MSSQL_SA_PASSWORD=YourStrong!Passw0rd
```

Test class: `CatalogSqlServerIT` annotated with `@Tag("gravitino-docker-test")`.

### 15.2 Test Cases

| Test Case                    | Description                                                                                            |
|------------------------------|--------------------------------------------------------------------------------------------------------|
| `testCreateAndLoadSchema`    | Create schema, load, verify name.                                                                      |
| `testDropSchema`             | Drop empty schema; drop non-empty with cascade=true; drop non-empty with cascade=false (expect error). |
| `testListSchemas`            | Ensure system schemas are filtered, `dbo` is visible.                                                  |
| `testCreateAndLoadTable`     | Create table with various types, IDENTITY, defaults, primary key. Verify all metadata round-trips.     |
| `testAlterTable`             | Add, drop, rename, modify columns; update comments; update default values.                             |
| `testDropTable`              | Verify immediate deletion.                                                                             |
| `testRenameTable`            | Rename table within same schema.                                                                       |
| `testDefaultValues`          | Round-trip of `GETDATE()`, string literals, numeric literals, `NEWID()`, `NULL`.                       |
| `testIdentityColumn`         | Create with IDENTITY, verify `autoIncrement=true` on load. Verify ALTER IDENTITY is rejected.          |
| `testTypeMapping`            | Verify all type mappings (SQL Server → Gravitino → SQL Server).                                        |
| `testNvarcharMapping`        | Verify `nvarchar` → `StringType` and `StringType` → `nvarchar` one-to-one mapping.                     |
| `testTableIndexes`           | Create table with PRIMARY_KEY and UNIQUE_KEY, verify on load.                                          |
| `testExceptionMapping`       | Trigger errors (duplicate table, non-existent schema, etc.), verify correct Gravitino exceptions.      |
| `testNameLength`             | Verify 128-character identifier limit.                                                                 |
| `testSchemaComment`          | Verify that creating schema with comment is rejected (supportSchemaComment=false).                     |
| `testTableAndColumnComments` | Verify comments stored/retrieved via extended properties.                                              |

## 16. Implementation Priority

| Priority | Item                                                              |
|----------|-------------------------------------------------------------------|
| P0       | Type conversion (`SqlServerTypeConverter`)                        |
| P0       | Schema operations (create, drop, list, load)                      |
| P0       | Table operations (create, drop, load, list)                       |
| P0       | Default value conversion (`SqlServerColumnDefaultValueConverter`) |
| P0       | Exception mapping (`SqlServerExceptionConverter`)                 |
| P0       | Catalog capability (`SqlServerCatalogCapability`)                 |
| P1       | `IDENTITY` (auto-increment) support                               |
| P1       | PRIMARY_KEY and UNIQUE_KEY index support                          |
| P1       | Full `ALTER TABLE` (add/drop/modify/rename columns, comments)     |
| P1       | Integration tests (`CatalogSqlServerIT`)                          |
| P1       | Unit tests for all converters and operations                      |
| P2       | Documentation (`docs/jdbc-sqlserver-catalog.md`)                  |
| Future   | View support (pending framework enhancement)                      |
| Future   | Entra ID / Managed Identity authentication                        |
| Future   | Partitioned table detection                                       |
| Future   | Native `NVarCharType` in Gravitino type system                    |

## 17. References

- [SQL Server Data Types (Transact-SQL)](https://learn.microsoft.com/en-us/sql/t-sql/data-types/data-types-transact-sql)
- [CREATE TABLE (Transact-SQL)](https://learn.microsoft.com/en-us/sql/t-sql/statements/create-table-transact-sql)
- [ALTER TABLE (Transact-SQL)](https://learn.microsoft.com/en-us/sql/t-sql/statements/alter-table-transact-sql)
- [CREATE SCHEMA (Transact-SQL)](https://learn.microsoft.com/en-us/sql/t-sql/statements/create-schema-transact-sql)
- [sp_addextendedproperty (Transact-SQL)](https://learn.microsoft.com/en-us/sql/relational-databases/system-stored-procedures/sp-addextendedproperty-transact-sql)
- [IDENTITY Property (Transact-SQL)](https://learn.microsoft.com/en-us/sql/t-sql/statements/create-table-transact-sql-identity-property)
- [Microsoft JDBC Driver for SQL Server (GitHub)](https://github.com/microsoft/mssql-jdbc) — MIT License
- [Microsoft JDBC Driver on Maven Central](https://central.sonatype.com/artifact/com.microsoft.sqlserver/mssql-jdbc)
- [SQL Server Version Support Lifecycle](https://learn.microsoft.com/en-us/lifecycle/products/?products=sql-server)
- [Gravitino JDBC Common Framework](https://github.com/apache/gravitino/tree/main/catalogs/catalog-jdbc-common)
- [Gravitino PostgreSQL Catalog](https://github.com/apache/gravitino/tree/main/catalogs/catalog-jdbc-postgresql) — Primary reference implementation
- [Gravitino MySQL Catalog](https://github.com/apache/gravitino/tree/main/catalogs/catalog-jdbc-mysql)
- [Gravitino ViewCatalog Interface](https://github.com/apache/gravitino/blob/main/api/src/main/java/org/apache/gravitino/rel/ViewCatalog.java) — `@Unstable`
