call gravitino.system.create_catalog(
    'gt_glue_xxx1',
    'glue',
    map(
        array['aws-region', 'aws-access-key-id', 'aws-secret-access-key', 'aws-glue-endpoint', 'warehouse'],
        array['${aws_region}', '${aws_access_key}', '${aws_secret_key}', '${?aws_glue_endpoint}', '${warehouse}']
    )
);

show catalogs like 'gt_glue_xxx1';

CALL gravitino.system.drop_catalog('gt_glue_xxx1');

show catalogs like 'gt_glue_xxx1';

call gravitino.system.create_catalog(
    'gt_glue_xxx1',
    'glue',
    map(
        array['aws-region', 'aws-access-key-id', 'aws-secret-access-key', 'aws-glue-endpoint', 'warehouse'],
        array['${aws_region}', '${aws_access_key}', '${aws_secret_key}', '${?aws_glue_endpoint}', '${warehouse}']
    )
);

show catalogs like 'gt_glue_xxx1';

CALL gravitino.system.drop_catalog('gt_glue_xxx1');
