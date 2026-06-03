CREATE SCHEMA gt_glue.gt_db3;

USE gt_glue.gt_db3;

-- Create a Hive table (default format)
CREATE TABLE hive_employee (
    id int,
    name varchar,
    salary double
);

-- Create an Iceberg table in the same schema
CREATE TABLE iceberg_orders (
    order_id bigint,
    customer varchar,
    amount decimal(10,2)
) WITH (type = 'iceberg');

-- Insert into Hive table
insert into hive_employee values (1, 'Alice', 75000.0), (2, 'Bob', 82000.0), (3, 'Charlie', 90000.0);

-- Insert into Iceberg table
insert into iceberg_orders values (1001, 'CustomerA', DECIMAL '250.50'), (1002, 'CustomerB', DECIMAL '1200.00');

-- Query Hive table
select name from hive_employee order by id;

-- Query Iceberg table
select customer from iceberg_orders order by order_id;

-- Show both tables coexist in the same schema
show tables;

-- Cross-format join
select e.name, o.customer from hive_employee e, iceberg_orders o where e.id = 1 and o.order_id = 1001;

drop table gt_glue.gt_db3.iceberg_orders;

drop table gt_glue.gt_db3.hive_employee;

drop schema gt_glue.gt_db3;

