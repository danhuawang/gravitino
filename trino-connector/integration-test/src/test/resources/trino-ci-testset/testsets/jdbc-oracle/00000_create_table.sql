SHOW SCHEMAS FROM gt_oracle LIKE 'gravitino';

CREATE TABLE gt_oracle.gravitino.gt_oracle_create_table (
    name varchar(200),
    salary decimal(10, 2)
);

SHOW TABLES FROM gt_oracle.gravitino LIKE 'gt_oracle_create_table';

DROP TABLE gt_oracle.gravitino.gt_oracle_create_table;

SHOW TABLES FROM gt_oracle.gravitino LIKE 'gt_oracle_create_table';

DROP TABLE IF EXISTS gt_oracle.gravitino.gt_oracle_create_table;
