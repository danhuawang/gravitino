CREATE SCHEMA gt_glue.gt_iceberg_db3;

USE gt_glue.gt_iceberg_db3;

CREATE TABLE tb01 (
    f1 VARCHAR,
    f3 VARBINARY,
    f4 DECIMAL(10, 3),
    f5 REAL,
    f6 DOUBLE,
    f7 BOOLEAN,
    f10 INT,
    f11 INTEGER,
    f12 BIGINT,
    f13 DATE,
    f14 TIME,
    f15 TIMESTAMP,
    f16 TIMESTAMP WITH TIME ZONE
) WITH (type = 'iceberg');

SHOW CREATE TABLE tb01;

INSERT INTO tb01 (f1, f3, f4, f5, f6, f7, f10, f11, f12, f13, f14, f15, f16)
VALUES ('Sample text 1', x'65', 123.456, 7.89, 12.34, true, 1000, 1000, 100000, DATE '2024-01-01', TIME '08:00:00',
        TIMESTAMP '2024-01-01 08:00:00', TIMESTAMP '2024-01-01 08:00:00 UTC');

INSERT INTO tb01 (f1, f3, f4, f5, f6, f7, f10, f11, f12, f13, f14, f15, f16)
VALUES (NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

SELECT * FROM tb01 ORDER BY f1;

DROP TABLE gt_glue.gt_iceberg_db3.tb01;

DROP SCHEMA gt_glue.gt_iceberg_db3;
