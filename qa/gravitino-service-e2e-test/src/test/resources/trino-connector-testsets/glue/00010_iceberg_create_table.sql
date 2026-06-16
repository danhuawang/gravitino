CREATE SCHEMA gt_glue.gt_iceberg_db1;

USE gt_glue.gt_iceberg_db1;

CREATE TABLE tb01 (
    name varchar,
    salary int
) WITH (type = 'iceberg');

show create table tb01;

CREATE TABLE tb02 (
    name varchar,
    salary int
) WITH (type = 'iceberg', format = 'ORC');

show create table tb02;

CREATE TABLE tb03 (
    name varchar,
    salary int,
    region varchar
) WITH (type = 'iceberg', partitioned_by = ARRAY['region']);

show create table tb03;

CREATE TABLE IF NOT EXISTS tb01 (
    name varchar,
    salary int
) WITH (type = 'iceberg');

drop table gt_glue.gt_iceberg_db1.tb01;

drop table gt_glue.gt_iceberg_db1.tb02;

drop table gt_glue.gt_iceberg_db1.tb03;

drop schema gt_glue.gt_iceberg_db1;
