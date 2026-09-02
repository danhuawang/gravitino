--
-- Copyright 2026 Datastrato Inc.
--

-- Enterprise extension JDBC schema.

CREATE TABLE IF NOT EXISTS `scim_token_meta` (
    `token_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'token id',
    `token_name` VARCHAR(256) NOT NULL COMMENT 'scim token name',
    `token_hash` VARCHAR(64) NOT NULL COMMENT 'SHA-256 hex digest of scim token value',
    `expires_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'token expiry time in ms, 0 means never expires',
    `audit_info` MEDIUMTEXT NOT NULL COMMENT 'scim token audit info',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'scim token deleted at',
    `updated_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'updated at',
    PRIMARY KEY (`token_id`),
    UNIQUE KEY `uk_stm_tn_del` (`token_name`, `deleted_at`),
    UNIQUE KEY `uk_stm_hash_del` (`token_hash`, `deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT 'scim token metadata';

CREATE TABLE IF NOT EXISTS `scim_user_meta` (
    `user_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'scim user id',
    `user_name` VARCHAR(128) NOT NULL COMMENT 'scim username',
    `user_name_normalized` VARCHAR(128) AS LOWER(`user_name`),
    `external_id` VARCHAR(256) NULL COMMENT 'SCIM externalId',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'maps SCIM User active, 0 disabled, 1 enabled',
    `current_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'scim user current version',
    `last_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'scim user last version',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'scim user deleted at',
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_sun_del` (`user_name`, `deleted_at`),
    UNIQUE KEY `uk_sun_norm_del` (`user_name_normalized`, `deleted_at`),
    UNIQUE KEY `uk_sue_del` (`external_id`, `deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT 'scim user metadata';

CREATE TABLE IF NOT EXISTS `scim_group_meta` (
    `group_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'scim group id',
    `group_name` VARCHAR(128) NOT NULL COMMENT 'scim group name',
    `group_name_normalized` VARCHAR(128) AS LOWER(`group_name`),
    `group_comment` VARCHAR(1024) DEFAULT '' COMMENT 'scim group comment',
    `external_id` VARCHAR(256) NULL COMMENT 'SCIM externalId',
    `current_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'scim group current version',
    `last_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'scim group last version',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'scim group deleted at',
    PRIMARY KEY (`group_id`),
    UNIQUE KEY `uk_sgn_del` (`group_name`, `deleted_at`),
    UNIQUE KEY `uk_sgn_norm_del` (`group_name_normalized`, `deleted_at`),
    UNIQUE KEY `uk_sge_del` (`external_id`, `deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT 'scim group metadata';

CREATE TABLE IF NOT EXISTS `scim_user_group_rel` (
    `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'auto increment id',
    `user_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'scim user id',
    `group_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'scim group id',
    `current_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'relation current version',
    `last_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'relation last version',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'relation deleted at',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sugr_ui_gi_del` (`user_id`, `group_id`, `deleted_at`),
    KEY `idx_sugr_uid` (`user_id`),
    KEY `idx_sugr_gid` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT 'scim user group relation';

CREATE TABLE IF NOT EXISTS `scim_error_history` (
    `error_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'error history id',
    `http_method` VARCHAR(16) NOT NULL COMMENT 'HTTP method',
    `request_path` VARCHAR(1024) NOT NULL COMMENT 'SCIM request path',
    `http_status` INT NOT NULL COMMENT 'HTTP status code',
    `scim_type` VARCHAR(64) DEFAULT NULL COMMENT 'RFC 7644 scimType when present',
    `error_detail` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT 'truncated SCIM error detail',
    `principal` VARCHAR(256) NOT NULL DEFAULT '' COMMENT 'authenticated SCIM token name',
    `created_at` BIGINT(20) UNSIGNED NOT NULL COMMENT 'created at in ms',
    PRIMARY KEY (`error_id`),
    KEY `idx_seh_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT 'scim protocol error history';

CREATE TABLE IF NOT EXISTS `catalog_connection_test_meta` (
    `catalog_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'catalog id',
    `type` VARCHAR(256) NOT NULL COMMENT 'Catalog or credential connection test type',
    `catalog_version` INT UNSIGNED NOT NULL COMMENT 'tested catalog version',
    `test_status` VARCHAR(16) NOT NULL COMMENT 'completed connection test status',
    `last_tested_at` BIGINT(20) UNSIGNED NOT NULL COMMENT 'test completion time in ms',
    `error_message` VARCHAR(4096) DEFAULT NULL COMMENT 'safe connection failure message',
    PRIMARY KEY (`catalog_id`, `type`)
) ENGINE=InnoDB;
