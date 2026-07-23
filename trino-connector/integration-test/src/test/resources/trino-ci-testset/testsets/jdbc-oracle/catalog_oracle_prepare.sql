CALL gravitino.system.create_catalog(
    'gt_oracle',
    'jdbc-oracle',
    map(
        array['jdbc-url', 'jdbc-user', 'jdbc-password', 'jdbc-driver'],
        array['${oracle_uri}', 'GRAVITINO', 'gravitino', 'oracle.jdbc.OracleDriver']
    )
);
