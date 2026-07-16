CREATE TABLE gt_sqlserver.dbo.gt_sqlserver_datatype_more (
    f_char char(10),
    f_varchar varchar(200),
    f_integer integer,
    f_bigint bigint,
    f_decimal decimal(12, 3),
    f_double double,
    f_date date,
    f_timestamp3 timestamp(3),
    f_timestamp6 timestamp(6)
);

SHOW COLUMNS FROM gt_sqlserver.dbo.gt_sqlserver_datatype_more;

INSERT INTO gt_sqlserver.dbo.gt_sqlserver_datatype_more(
    f_char,
    f_varchar,
    f_integer,
    f_bigint,
    f_decimal,
    f_double,
    f_date,
    f_timestamp3,
    f_timestamp6
)
VALUES (
    'Text1',
    'Sample text 1',
    42,
    9000000000,
    CAST(123.456 AS decimal(12, 3)),
    12.34,
    DATE '2024-01-01',
    TIMESTAMP '2024-01-01 08:00:00.123',
    TIMESTAMP '2024-01-01 08:00:00.123456'
);

INSERT INTO gt_sqlserver.dbo.gt_sqlserver_datatype_more(
    f_char,
    f_varchar,
    f_integer,
    f_bigint,
    f_decimal,
    f_double,
    f_date,
    f_timestamp3,
    f_timestamp6
)
VALUES (NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

SELECT
    f_char,
    f_varchar,
    f_integer,
    f_bigint,
    f_decimal,
    f_double,
    f_date,
    f_timestamp3,
    f_timestamp6
FROM gt_sqlserver.dbo.gt_sqlserver_datatype_more
ORDER BY f_varchar;

DROP TABLE gt_sqlserver.dbo.gt_sqlserver_datatype_more;
