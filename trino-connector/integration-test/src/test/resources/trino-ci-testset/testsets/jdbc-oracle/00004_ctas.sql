CREATE TABLE gt_oracle.gravitino.gt_oracle_ctas_source (
    name varchar(200)
);

INSERT INTO gt_oracle.gravitino.gt_oracle_ctas_source(name)
VALUES
    ('alice'),
    ('bob'),
    ('charlie');

CREATE TABLE gt_oracle.gravitino.gt_oracle_ctas_basic AS
SELECT * FROM gt_oracle.gravitino.gt_oracle_ctas_source;

SELECT * FROM gt_oracle.gravitino.gt_oracle_ctas_basic ORDER BY name;

CREATE TABLE gt_oracle.gravitino.gt_oracle_ctas_transform AS
SELECT upper(name) AS upper_name
FROM gt_oracle.gravitino.gt_oracle_ctas_source
WHERE name > 'alice';

SELECT * FROM gt_oracle.gravitino.gt_oracle_ctas_transform ORDER BY upper_name;

CREATE TABLE gt_oracle.gravitino.gt_oracle_ctas_empty AS
SELECT * FROM gt_oracle.gravitino.gt_oracle_ctas_source
WHERE name = 'nobody';

SELECT count(*) FROM gt_oracle.gravitino.gt_oracle_ctas_empty;

DROP TABLE gt_oracle.gravitino.gt_oracle_ctas_empty;

DROP TABLE gt_oracle.gravitino.gt_oracle_ctas_transform;

DROP TABLE gt_oracle.gravitino.gt_oracle_ctas_basic;

DROP TABLE gt_oracle.gravitino.gt_oracle_ctas_source;
