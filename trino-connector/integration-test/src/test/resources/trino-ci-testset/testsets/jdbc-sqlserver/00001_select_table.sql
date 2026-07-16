CREATE SCHEMA gt_sqlserver.gt_db1;

CREATE TABLE gt_sqlserver.gt_db1.tb01 (
    name varchar(200),
    salary int
);

insert into gt_sqlserver.gt_db1.tb01(name, salary) values ('sam', 11);
insert into gt_sqlserver.gt_db1.tb01(name, salary) values ('jerry', 13);
insert into gt_sqlserver.gt_db1.tb01(name, salary) values ('bob', 14), ('tom', 12);

select * from gt_sqlserver.gt_db1.tb01 order by name;

CREATE TABLE gt_sqlserver.gt_db1.tb02 (
    name varchar(200),
    salary int
);

insert into gt_sqlserver.gt_db1.tb02(name, salary) select * from gt_sqlserver.gt_db1.tb01 order by name;

select * from gt_sqlserver.gt_db1.tb02 order by name;

drop table gt_sqlserver.gt_db1.tb02;

drop table gt_sqlserver.gt_db1.tb01;

drop table IF EXISTS gt_sqlserver.gt_db1.tb01;

drop schema gt_sqlserver.gt_db1;
