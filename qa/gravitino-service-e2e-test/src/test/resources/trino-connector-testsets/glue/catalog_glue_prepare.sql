call gravitino.system.create_catalog(
    'gt_glue',
    'glue',
    map(
        array['aws-region', 'aws-access-key-id', 'aws-secret-access-key', 'aws-glue-endpoint', 'warehouse'],
        array['${aws_region}', '${aws_access_key}', '${aws_secret_key}', '${?aws_glue_endpoint}', '${?warehouse}']
    )
);

show catalogs like 'gt_glue';

-- Clean up any schemas left over from previous failed test runs.
DROP SCHEMA IF EXISTS gt_glue.gt_db0 CASCADE;
DROP SCHEMA IF EXISTS gt_glue.gt_db1 CASCADE;
DROP SCHEMA IF EXISTS gt_glue.gt_db2 CASCADE;
DROP SCHEMA IF EXISTS gt_glue.gt_db3 CASCADE;
DROP SCHEMA IF EXISTS gt_glue.gt_pbs_db1 CASCADE;
DROP SCHEMA IF EXISTS gt_glue.varchar_db1 CASCADE;
DROP SCHEMA IF EXISTS gt_glue.gt_decimal_db1 CASCADE;
DROP SCHEMA IF EXISTS gt_glue.gt_array_db1 CASCADE;
DROP SCHEMA IF EXISTS gt_glue.gt_map_db1 CASCADE;
DROP SCHEMA IF EXISTS gt_glue.gt_iceberg_db1 CASCADE;
DROP SCHEMA IF EXISTS gt_glue.gt_iceberg_db2 CASCADE;