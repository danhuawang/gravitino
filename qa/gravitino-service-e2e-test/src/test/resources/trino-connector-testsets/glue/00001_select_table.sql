CREATE SCHEMA gt_glue.gt_db1;

CREATE TABLE gt_glue.gt_db1.tb01 (
    name varchar,
    salary int
);

insert into gt_glue.gt_db1.tb01(name, salary) values ('sam', 11);
insert into gt_glue.gt_db1.tb01(name, salary) values ('jerry', 13);
insert into gt_glue.gt_db1.tb01(name, salary) values ('bob', 14), ('tom', 12);

select * from gt_glue.gt_db1.tb01 order by name;

CREATE TABLE gt_glue.gt_db1.tb02 (
    name varchar,
    salary int
);

insert into gt_glue.gt_db1.tb02(name, salary) select * from gt_glue.gt_db1.tb01 order by name;

select * from gt_glue.gt_db1.tb02 order by name;

drop table gt_glue.gt_db1.tb02;

drop table gt_glue.gt_db1.tb01;

drop schema gt_glue.gt_db1;
