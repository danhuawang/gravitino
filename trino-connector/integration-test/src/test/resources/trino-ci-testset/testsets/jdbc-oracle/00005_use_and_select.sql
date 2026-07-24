USE gt_oracle.gravitino;

CREATE TABLE gt_oracle_use_table (
    name varchar(200),
    salary decimal(10, 2)
);

INSERT INTO gt_oracle_use_table(name, salary)
VALUES
    ('sam', CAST(11.00 AS decimal(10, 2))),
    ('jerry', CAST(13.00 AS decimal(10, 2))),
    ('bob', CAST(14.00 AS decimal(10, 2))),
    ('tom', CAST(12.00 AS decimal(10, 2)));

CREATE TABLE gt_oracle_use_copy (
    name varchar(200),
    salary decimal(10, 2)
);

INSERT INTO gt_oracle_use_copy(name, salary)
SELECT name, salary FROM gt_oracle_use_table ORDER BY name;

SELECT * FROM gt_oracle_use_copy ORDER BY name;

SELECT gt_oracle_use_table.name, gt_oracle_use_table.salary
FROM gt_oracle_use_table
JOIN gt_oracle_use_copy t ON gt_oracle_use_table.salary = t.salary
ORDER BY gt_oracle_use_table.name;

SHOW TABLES;

DROP TABLE gt_oracle_use_copy;

DROP TABLE gt_oracle_use_table;
