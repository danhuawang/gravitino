--
-- Copyright 2026 Datastrato Pvt Ltd.
-- This software is licensed under the Apache License version 2.
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
    `last_used_at` BIGINT(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'last authenticated SCIM request time in ms, 0 means never',
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
