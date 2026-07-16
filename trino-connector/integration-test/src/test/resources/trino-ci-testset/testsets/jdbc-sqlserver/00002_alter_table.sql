CREATE SCHEMA gt_sqlserver.gt_db1;

CREATE TABLE gt_sqlserver.gt_db1.tb01 (
    name varchar(200),
    salary int,
    city int
);

alter table gt_sqlserver.gt_db1.tb01 rename to gt_sqlserver.gt_db1.tb03;
show tables from gt_sqlserver.gt_db1;

alter table gt_sqlserver.gt_db1.tb03 rename to gt_sqlserver.gt_db1.tb01;
show tables from gt_sqlserver.gt_db1;

alter table gt_sqlserver.gt_db1.tb01 drop column city;
show create table gt_sqlserver.gt_db1.tb01;

comment on table gt_sqlserver.gt_db1.tb01 is 'test table comments';
show create table gt_sqlserver.gt_db1.tb01;

alter table gt_sqlserver.gt_db1.tb01 rename column name to s;
show create table gt_sqlserver.gt_db1.tb01;

alter table gt_sqlserver.gt_db1.tb01 add column city varchar(200);
show create table gt_sqlserver.gt_db1.tb01;

SHOW COLUMNS FROM gt_sqlserver.gt_db1.tb01;

SHOW COLUMNS FROM gt_sqlserver.gt_db1.tb01 LIKE 's%';

drop table gt_sqlserver.gt_db1.tb01;

drop schema gt_sqlserver.gt_db1;
