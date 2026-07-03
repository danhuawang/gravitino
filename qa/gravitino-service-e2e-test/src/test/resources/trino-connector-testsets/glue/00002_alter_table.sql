CREATE SCHEMA gt_glue.gt_db2;

CREATE TABLE gt_glue.gt_db2.tb01 (
    name varchar,
    salary int,
    city int
);

alter table gt_glue.gt_db2.tb01 drop column city;
show create table gt_glue.gt_db2.tb01;

alter table gt_glue.gt_db2.tb01 rename column name to s;
show create table gt_glue.gt_db2.tb01;

comment on table gt_glue.gt_db2.tb01 is 'test table comments';
show create table gt_glue.gt_db2.tb01;

comment on column gt_glue.gt_db2.tb01.s is 'test column comments';
show create table gt_glue.gt_db2.tb01;

alter table gt_glue.gt_db2.tb01 add column city varchar comment 'aaa';
show create table gt_glue.gt_db2.tb01;

drop table gt_glue.gt_db2.tb01;

drop schema gt_glue.gt_db2;
