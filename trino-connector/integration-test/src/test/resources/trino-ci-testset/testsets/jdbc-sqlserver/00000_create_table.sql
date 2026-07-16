CREATE SCHEMA gt_sqlserver.gt_db1;

CREATE SCHEMA IF NOT EXISTS gt_sqlserver.gt_db2;

SHOW SCHEMAS FROM gt_sqlserver like 'gt_db1';

SHOW CREATE SCHEMA gt_sqlserver.gt_db1;

CREATE SCHEMA IF NOT EXISTS gt_sqlserver.gt_db1;

CREATE TABLE gt_sqlserver.gt_db1.tb01 (
    name varchar(200),
    salary int
) COMMENT 'OKK';

SHOW CREATE TABLE gt_sqlserver.gt_db1.tb01;

CREATE TABLE IF NOT EXISTS gt_sqlserver.gt_db1.tb01 (
    name varchar(200),
    salary int
);

SHOW TABLES FROM gt_sqlserver.gt_db1 like 'tb01';

DROP TABLE IF EXISTS gt_sqlserver.gt_db1.tb01;

DROP SCHEMA gt_sqlserver.gt_db1;

DROP SCHEMA IF EXISTS gt_sqlserver.gt_db1;

DROP SCHEMA IF EXISTS gt_sqlserver.gt_db2;
