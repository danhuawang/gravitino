CREATE SCHEMA gt_glue.gt_iceberg_db2;

USE gt_glue.gt_iceberg_db2;

CREATE TABLE tb01 (
    name varchar,
    salary int
) WITH (type = 'iceberg');

insert into tb01(name, salary) values ('sam', 11);
insert into tb01(name, salary) values ('jerry', 13);
insert into tb01(name, salary) values ('bob', 14), ('tom', 12);

select * from tb01 order by name;

CREATE TABLE tb02 (
    name varchar,
    salary int
) WITH (type = 'iceberg');

insert into tb02(name, salary) select * from tb01 order by name;

select * from tb02 order by name;

drop table gt_glue.gt_iceberg_db2.tb02;

drop table gt_glue.gt_iceberg_db2.tb01;

drop schema gt_glue.gt_iceberg_db2;
