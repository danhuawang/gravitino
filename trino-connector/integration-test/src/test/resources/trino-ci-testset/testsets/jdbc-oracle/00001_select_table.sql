CREATE TABLE gt_oracle.gravitino.gt_oracle_select_table (
    name varchar(200),
    salary decimal(10, 2)
);

INSERT INTO gt_oracle.gravitino.gt_oracle_select_table(name, salary)
VALUES ('sam', CAST(11.00 AS decimal(10, 2)));

INSERT INTO gt_oracle.gravitino.gt_oracle_select_table(name, salary)
VALUES ('jerry', CAST(13.00 AS decimal(10, 2)));

INSERT INTO gt_oracle.gravitino.gt_oracle_select_table(name, salary)
VALUES ('bob', CAST(14.00 AS decimal(10, 2))), ('tom', CAST(12.00 AS decimal(10, 2)));

SELECT * FROM gt_oracle.gravitino.gt_oracle_select_table ORDER BY name;

SELECT name, salary
FROM gt_oracle.gravitino.gt_oracle_select_table
WHERE salary > CAST(12.00 AS decimal(10, 2))
ORDER BY salary;

SELECT count(*), sum(salary) FROM gt_oracle.gravitino.gt_oracle_select_table;

DROP TABLE gt_oracle.gravitino.gt_oracle_select_table;
