-- ============================================================
-- Spark DDL: View operations
-- Covers: CREATE VIEW, CREATE OR REPLACE VIEW, SHOW CREATE VIEW,
--         DESCRIBE VIEW, ALTER VIEW (rename, set/unset properties),
--         querying views, DROP VIEW
-- ===========================================================

CREATE DATABASE IF NOT EXISTS remote_irc_b.molly_spark_ddl_view;
USE remote_irc_b.molly_spark_ddl_view;

-- ----------------------------------------------------------------
-- 1. Base table for views
-- ----------------------------------------------------------------
DROP TABLE IF EXISTS remote_irc_b.molly_spark_ddl_view.products;

CREATE TABLE remote_irc_b.molly_spark_ddl_view.products (
  product_id   BIGINT,
  product_name STRING,
  category     STRING,
  price        DECIMAL(10, 2),
  stock        INT,
  created_date DATE
) USING iceberg;

INSERT INTO remote_irc_b.molly_spark_ddl_view.products VALUES
  (1, 'Laptop',     'Electronics', 999.99,  50, DATE '2024-01-10'),
  (2, 'Headphones', 'Electronics', 149.99, 200, DATE '2024-02-15'),
  (3, 'Desk Chair', 'Furniture',   299.99,  30, DATE '2024-03-01'),
  (4, 'Notebook',   'Stationery',    4.99, 500, DATE '2024-01-20'),
  (5, 'Monitor',    'Electronics', 399.99,  80, DATE '2024-04-05');

-- ----------------------------------------------------------------
-- 2. CREATE VIEW: simple projection
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS remote_irc_b.molly_spark_ddl_view.electronics_view;

CREATE VIEW remote_irc_b.molly_spark_ddl_view.electronics_view
  COMMENT 'Products in the Electronics category'
AS
SELECT product_id, product_name, price, stock
FROM remote_irc_b.molly_spark_ddl_view.products
WHERE category = 'Electronics';

SELECT * FROM remote_irc_b.molly_spark_ddl_view.electronics_view ORDER BY product_id;

-- ----------------------------------------------------------------
-- 3. DESCRIBE VIEW
-- ----------------------------------------------------------------
DESCRIBE remote_irc_b.molly_spark_ddl_view.electronics_view;

-- ----------------------------------------------------------------
-- 4. SHOW CREATE TABLE (works for views too in Spark)
-- ----------------------------------------------------------------
SHOW CREATE TABLE remote_irc_b.molly_spark_ddl_view.electronics_view;

-- ----------------------------------------------------------------
-- 5. CREATE VIEW: aggregation view
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS remote_irc_b.molly_spark_ddl_view.category_summary_view;

CREATE VIEW remote_irc_b.molly_spark_ddl_view.category_summary_view
  COMMENT 'Per-category product count and average price'
AS
SELECT
  category,
  COUNT(*)   AS product_count,
  AVG(price) AS avg_price,
  SUM(stock) AS total_stock
FROM remote_irc_b.molly_spark_ddl_view.products
GROUP BY category;

SELECT * FROM remote_irc_b.molly_spark_ddl_view.category_summary_view ORDER BY category;

-- ----------------------------------------------------------------
-- 6. CREATE OR REPLACE VIEW: update the definition
-- ----------------------------------------------------------------
CREATE OR REPLACE VIEW remote_irc_b.molly_spark_ddl_view.electronics_view
  COMMENT 'Electronics products with stock > 60'
AS
SELECT product_id, product_name, price, stock
FROM remote_irc_b.molly_spark_ddl_view.products
WHERE category = 'Electronics' AND stock > 60;

SELECT * FROM remote_irc_b.molly_spark_ddl_view.electronics_view ORDER BY product_id;

-- ----------------------------------------------------------------
-- 7. View on top of view (nested view)
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS remote_irc_b.molly_spark_ddl_view.affordable_electronics_view;

CREATE VIEW remote_irc_b.molly_spark_ddl_view.affordable_electronics_view
  COMMENT 'Electronics under $500 with sufficient stock'
AS
SELECT product_id, product_name, price
FROM remote_irc_b.molly_spark_ddl_view.electronics_view
WHERE price < 500.00;

SELECT * FROM remote_irc_b.molly_spark_ddl_view.affordable_electronics_view ORDER BY price;

-- ----------------------------------------------------------------
-- 8. SHOW VIEWS
-- ----------------------------------------------------------------
SHOW VIEWS IN remote_irc_b.molly_spark_ddl_view;

-- ----------------------------------------------------------------
-- 9. ALTER VIEW: rename
-- ----------------------------------------------------------------
ALTER VIEW remote_irc_b.molly_spark_ddl_view.affordable_electronics_view
  RENAME TO remote_irc_b.molly_spark_ddl_view.budget_electronics_view;

SHOW VIEWS IN remote_irc_b.molly_spark_ddl_view;

-- ----------------------------------------------------------------
-- 10. ALTER VIEW: set properties
-- ----------------------------------------------------------------
ALTER VIEW remote_irc_b.molly_spark_ddl_view.budget_electronics_view
  SET TBLPROPERTIES ('version' = '2');

-- ----------------------------------------------------------------
-- 11. ALTER VIEW: unset properties
-- ----------------------------------------------------------------
ALTER VIEW remote_irc_b.molly_spark_ddl_view.budget_electronics_view
  UNSET TBLPROPERTIES ('version');

-- ----------------------------------------------------------------
-- 12. DROP VIEW
-- ----------------------------------------------------------------
DROP VIEW IF EXISTS remote_irc_b.molly_spark_ddl_view.budget_electronics_view;
DROP VIEW IF EXISTS remote_irc_b.molly_spark_ddl_view.electronics_view;
DROP VIEW IF EXISTS remote_irc_b.molly_spark_ddl_view.category_summary_view;

SHOW VIEWS IN remote_irc_b.molly_spark_ddl_view;

-- ----------------------------------------------------------------
-- 13. Cleanup (drop tables first; Iceberg REST does not support CASCADE)
-- ----------------------------------------------------------------
DROP TABLE IF EXISTS remote_irc_b.molly_spark_ddl_view.products;
DROP DATABASE IF EXISTS remote_irc_b.molly_spark_ddl_view;
