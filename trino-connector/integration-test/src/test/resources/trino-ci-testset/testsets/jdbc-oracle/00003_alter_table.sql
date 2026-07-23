CREATE TABLE gt_oracle.gravitino.gt_oracle_alter_table (
    name varchar(40),
    salary decimal(10, 2),
    city varchar(40)
);

ALTER TABLE gt_oracle.gravitino.gt_oracle_alter_table DROP COLUMN city;

SHOW CREATE TABLE gt_oracle.gravitino.gt_oracle_alter_table;

ALTER TABLE gt_oracle.gravitino.gt_oracle_alter_table ALTER COLUMN salary SET DATA TYPE decimal(12, 2);

SHOW CREATE TABLE gt_oracle.gravitino.gt_oracle_alter_table;

COMMENT ON TABLE gt_oracle.gravitino.gt_oracle_alter_table IS 'oracle alter table comments';

SHOW CREATE TABLE gt_oracle.gravitino.gt_oracle_alter_table;

ALTER TABLE gt_oracle.gravitino.gt_oracle_alter_table RENAME COLUMN name TO employee_name;

SHOW CREATE TABLE gt_oracle.gravitino.gt_oracle_alter_table;

COMMENT ON COLUMN gt_oracle.gravitino.gt_oracle_alter_table.employee_name IS 'employee name comments';

SHOW CREATE TABLE gt_oracle.gravitino.gt_oracle_alter_table;

ALTER TABLE gt_oracle.gravitino.gt_oracle_alter_table ADD COLUMN department varchar(40) COMMENT 'department comments';

SHOW COLUMNS FROM gt_oracle.gravitino.gt_oracle_alter_table;

ALTER TABLE gt_oracle.gravitino.gt_oracle_alter_table RENAME TO gt_oracle.gravitino.gt_oracle_alter_table_renamed;

SHOW TABLES FROM gt_oracle.gravitino LIKE 'gt_oracle_alter_table_renamed';

DROP TABLE gt_oracle.gravitino.gt_oracle_alter_table_renamed;
