CREATE SCHEMA gt_glue.varchar_db1;

USE gt_glue.varchar_db1;

CREATE TABLE tb01 (id int, name char(20));

CREATE TABLE tb02 (id int, name char(255));

CREATE TABLE tb03 (id int, name varchar(250));

CREATE TABLE tb04 (id int, name varchar(65535));

CREATE TABLE tb05 (id int, name char);

CREATE TABLE tb06 (id int, name varchar);

drop schema gt_glue.varchar_db1 cascade;
