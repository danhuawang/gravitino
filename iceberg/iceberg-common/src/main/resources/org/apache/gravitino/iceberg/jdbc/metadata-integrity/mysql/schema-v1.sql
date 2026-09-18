--
-- Copyright 2026 Datastrato Inc.
--

-- Catalog-local trusted metadata schema for MySQL-backed Iceberg JDBC catalogs.
-- Run Iceberg's own catalog DDL first: the identity columns reference iceberg_tables, so
-- a rename or drop of the pointer row cascades to its integrity row. Both tables must be
-- InnoDB and no charset clause is declared: both inherit the database default.
CREATE TABLE IF NOT EXISTS iceberg_metadata_integrity (
    catalog_name VARCHAR(255) NOT NULL,
    table_namespace VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    metadata_location VARCHAR(1000) NOT NULL,
    table_key_id VARCHAR(2048) NOT NULL,
    hash_algorithm VARCHAR(32) NOT NULL,
    metadata_hash VARCHAR(128) NOT NULL,
    PRIMARY KEY (catalog_name, table_namespace, table_name),
    FOREIGN KEY (catalog_name, table_namespace, table_name)
        REFERENCES iceberg_tables (catalog_name, table_namespace, table_name)
        ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB;
