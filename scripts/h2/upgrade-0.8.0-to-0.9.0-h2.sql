--
-- Copyright 2023 Datastrato Pvt Ltd.
-- This software is licensed under the Apache License version 2.
--

CREATE TABLE IF NOT EXISTS `dashboard_metrics` (
    `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'auto increment id',
    `metalake_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'metalake id',
    `user_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'user id who owns this metric',
    `metric_name` VARCHAR(64) NOT NULL COMMENT 'metric name, such as catalogCount, schemaCount, etc.',
    `metric_value` DOUBLE NOT NULL DEFAULT 0.0 COMMENT 'metric value',
    `created_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'metric created time',
    PRIMARY KEY (id),
    KEY `idx_mi_ui_ct` (`metalake_id`, `user_id`, `created_time`),
    KEY `idx_ct_da` (`created_time`)
) ENGINE=InnoDB;