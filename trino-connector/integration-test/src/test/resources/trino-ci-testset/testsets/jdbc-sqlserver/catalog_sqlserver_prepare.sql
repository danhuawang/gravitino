call gravitino.system.create_catalog(
    'gt_sqlserver',
    'jdbc-sqlserver',
    map(
        array['jdbc-url', 'jdbc-user', 'jdbc-password', 'jdbc-database', 'jdbc-driver'],
        array['${sqlserver_uri}', 'sa', 'GravitinoDs_123!', 'gt_db', 'com.microsoft.sqlserver.jdbc.SQLServerDriver']
    )
);
