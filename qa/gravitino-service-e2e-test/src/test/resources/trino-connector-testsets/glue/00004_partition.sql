CREATE SCHEMA gt_glue.gt_pbs_db1;

USE gt_glue.gt_pbs_db1;

CREATE TABLE nation (
  nationkey bigint,
  name varchar(25),
  regionkey bigint,
  comment varchar(152)
);

insert into nation select * from tpch.tiny.nation;

CREATE TABLE tb01 (
    n_nationkey bigint,
    n_name varchar,
    n_regionkey bigint,
    n_comment varchar,
    part_key varchar
) WITH (partitioned_by = ARRAY['part_key']);

INSERT INTO tb01 SELECT nationkey, name, regionkey, comment, name as part_key FROM nation;
INSERT INTO tb01 SELECT nationkey, name, regionkey, comment, name as part_key FROM nation;

CREATE TABLE tb02 (
    n_nationkey bigint,
    n_name varchar,
    n_regionkey bigint,
    n_comment varchar,
    part_key1 varchar,
    part_key2 bigint
) WITH (partitioned_by = ARRAY['part_key1', 'part_key2']);

INSERT INTO tb02 SELECT nationkey, name, regionkey, comment, name as part_key1, regionkey as part_key2 FROM nation;

SELECT count(*) FROM tb01 WHERE part_key='ALGERIA';

SELECT count(*) FROM tb01 WHERE n_regionkey=0;

select count(*) from tb02;

drop table tb01;

drop table tb02;

drop table nation;

drop schema gt_glue.gt_pbs_db1;
