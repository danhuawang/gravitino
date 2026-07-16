CREATE TABLE gt_sqlserver.dbo.gt_sqlserver_datatype (
    id varchar(10),
    name varchar(40),
    amount decimal(10, 2),
    created_date date,
    created_at timestamp
);

INSERT INTO gt_sqlserver.dbo.gt_sqlserver_datatype
VALUES (
    '1',
    'first',
    CAST(123.45 AS decimal(10, 2)),
    DATE '2024-01-01',
    TIMESTAMP '2024-01-01 08:00:00'
);

INSERT INTO gt_sqlserver.dbo.gt_sqlserver_datatype
VALUES (
    '2',
    'second',
    CAST(678.90 AS decimal(10, 2)),
    DATE '2024-01-02',
    TIMESTAMP '2024-01-02 09:30:00'
);

SELECT id, name, amount, created_date, created_at
FROM gt_sqlserver.dbo.gt_sqlserver_datatype
ORDER BY id;

DROP TABLE gt_sqlserver.dbo.gt_sqlserver_datatype;
