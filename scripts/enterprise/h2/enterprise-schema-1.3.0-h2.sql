--
-- Copyright 2026 Datastrato Inc..
--

-- Enterprise extension JDBC schema.

CREATE TABLE IF NOT EXISTS `scim_token_meta` (
    `token_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'token id',
    `metalake_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'metalake id',
    `token_name` VARCHAR(256) NOT NULL COMMENT 'scim token name',
    `token_hash` VARCHAR(64) NOT NULL COMMENT 'SHA-256 hex digest of scim token value',
    `expires_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'token expiry time in ms, 0 means never expires',
    `audit_info` CLOB NOT NULL COMMENT 'scim token audit info',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'scim token deleted at',
    `updated_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'updated at',
    PRIMARY KEY (`token_id`),
    CONSTRAINT `uk_stm_mid_tn_del` UNIQUE (`metalake_id`, `token_name`, `deleted_at`),
    CONSTRAINT `uk_stm_hash_del` UNIQUE (`token_hash`, `deleted_at`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `scim_user_group_rel` (
    `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'auto increment id',
    `metalake_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'metalake id',
    `user_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'user id',
    `group_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'group id',
    `audit_info` CLOB NOT NULL COMMENT 'relation audit info',
    `current_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'relation current version',
    `last_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'relation last version',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'relation deleted at',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_sugr_mid_ui_gi_del` UNIQUE (`metalake_id`, `user_id`, `group_id`, `deleted_at`),
    KEY `idx_sugr_mid` (`metalake_id`),
    KEY `idx_sugr_uid` (`user_id`),
    KEY `idx_sugr_gid` (`group_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `scim_error_history` (
    `error_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'error history id',
    `metalake_id` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'metalake id, 0 when unknown',
    `http_method` VARCHAR(16) NOT NULL COMMENT 'HTTP method',
    `request_path` VARCHAR(1024) NOT NULL COMMENT 'SCIM request path',
    `http_status` INT NOT NULL COMMENT 'HTTP status code',
    `scim_type` VARCHAR(64) DEFAULT NULL COMMENT 'RFC 7644 scimType when present',
    `error_detail` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT 'truncated SCIM error detail',
    `principal` VARCHAR(256) NOT NULL DEFAULT '' COMMENT 'authenticated SCIM token name',
    `created_at` BIGINT(20) UNSIGNED NOT NULL COMMENT 'created at in ms',
    PRIMARY KEY (`error_id`),
    KEY `idx_seh_mid_created` (`metalake_id`, `created_at`),
    KEY `idx_seh_created` (`created_at`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `v2_scim_token_meta` (
    `token_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'token id',
    `token_name` VARCHAR(256) NOT NULL COMMENT 'scim token name',
    `token_hash` VARCHAR(64) NOT NULL COMMENT 'SHA-256 hex digest of scim token value',
    `expires_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'token expiry time in ms, 0 means never expires',
    `audit_info` CLOB NOT NULL COMMENT 'scim token audit info',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'scim token deleted at',
    `updated_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'updated at',
    `last_used_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'last authenticated SCIM request time in ms, 0 means never',
    PRIMARY KEY (`token_id`),
    CONSTRAINT `uk_v2stm_tn_del` UNIQUE (`token_name`, `deleted_at`),
    CONSTRAINT `uk_v2stm_hash_del` UNIQUE (`token_hash`, `deleted_at`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `v2_scim_user_meta` (
    `user_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'scim user id',
    `user_name` VARCHAR(128) NOT NULL COMMENT 'scim username',
    `external_id` VARCHAR(256) NULL COMMENT 'SCIM externalId; returned as SCIM resource id',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'maps SCIM User active; 0 disabled, 1 enabled',
    `current_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'scim user current version',
    `last_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'scim user last version',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'scim user deleted at',
    PRIMARY KEY (`user_id`),
    CONSTRAINT `uk_v2sun_del` UNIQUE (`user_name`, `deleted_at`),
    CONSTRAINT `uk_v2sue_del` UNIQUE (`external_id`, `deleted_at`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `v2_scim_group_meta` (
    `group_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'scim group id',
    `group_name` VARCHAR(128) NOT NULL COMMENT 'scim group name',
    `group_comment` VARCHAR(1024) DEFAULT '' COMMENT 'scim group comment',
    `external_id` VARCHAR(256) NULL COMMENT 'SCIM externalId; returned as SCIM resource id',
    `current_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'scim group current version',
    `last_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'scim group last version',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'scim group deleted at',
    PRIMARY KEY (`group_id`),
    CONSTRAINT `uk_v2sgn_del` UNIQUE (`group_name`, `deleted_at`),
    CONSTRAINT `uk_v2sge_del` UNIQUE (`external_id`, `deleted_at`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `v2_scim_user_group_rel` (
    `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'auto increment id',
    `user_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'scim user id',
    `group_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'scim group id',
    `current_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'relation current version',
    `last_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'relation last version',
    `deleted_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'relation deleted at',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_v2sugr_ui_gi_del` UNIQUE (`user_id`, `group_id`, `deleted_at`),
    KEY `idx_v2sugr_uid` (`user_id`),
    KEY `idx_v2sugr_gid` (`group_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `v2_scim_error_history` (
    `error_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'error history id',
    `http_method` VARCHAR(16) NOT NULL COMMENT 'HTTP method',
    `request_path` VARCHAR(1024) NOT NULL COMMENT 'SCIM request path',
    `http_status` INT NOT NULL COMMENT 'HTTP status code',
    `scim_type` VARCHAR(64) DEFAULT NULL COMMENT 'RFC 7644 scimType when present',
    `error_detail` VARCHAR(1024) NOT NULL DEFAULT '' COMMENT 'truncated SCIM error detail',
    `principal` VARCHAR(256) NOT NULL DEFAULT '' COMMENT 'authenticated SCIM token name',
    `created_at` BIGINT(20) UNSIGNED NOT NULL COMMENT 'created at in ms',
    PRIMARY KEY (`error_id`),
    KEY `idx_v2seh_created` (`created_at`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `catalog_connection_test_meta` (
    `catalog_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'catalog id',
    `type` VARCHAR(256) NOT NULL COMMENT 'Catalog or credential connection test type',
    `catalog_version` INT UNSIGNED NOT NULL COMMENT 'tested catalog version',
    `test_status` VARCHAR(16) NOT NULL COMMENT 'completed connection test status',
    `last_tested_at` BIGINT(20) UNSIGNED NOT NULL COMMENT 'test completion time in ms',
    `error_message` VARCHAR(4096) DEFAULT NULL COMMENT 'safe connection failure message',
    PRIMARY KEY (`catalog_id`, `type`)
) ENGINE=InnoDB;
