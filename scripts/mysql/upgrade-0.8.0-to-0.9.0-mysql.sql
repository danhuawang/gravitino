--
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file--
--  distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"). You may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--  http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.
--

-- using default 'unknown' to fill in the new column for compatibility
ALTER TABLE `fileset_version_info` ADD COLUMN `storage_location_name` VARCHAR(256) NOT NULL DEFAULT 'unknown' COMMENT 'fileset storage location name' AFTER `properties`;
ALTER TABLE `fileset_version_info` DROP INDEX `uk_fid_ver_del`;
ALTER TABLE `fileset_version_info` ADD CONSTRAINT `uk_fid_ver_sto_del` UNIQUE KEY (`fileset_id`, `version`, `storage_location_name`, `deleted_at`);
-- remove the default value for storage_location_name
ALTER TABLE `fileset_version_info` ALTER COLUMN `storage_location_name` DROP DEFAULT;

-- Enterprise-specific table
CREATE TABLE IF NOT EXISTS `dashboard_metrics` (
    `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'auto increment id',
    `metalake_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'metalake id',
    `user_id` BIGINT(20) UNSIGNED NOT NULL COMMENT 'user id who owns this metric',
    `metric_name` VARCHAR(64) NOT NULL COMMENT 'metric name, such as catalogCount, schemaCount, etc.',
    `metric_value` DOUBLE NOT NULL DEFAULT 0.0 COMMENT 'metric value',
    `created_time` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'metric created time',
    PRIMARY KEY (id),
    KEY `idx_mi_ui_ct` (`metalake_id`, `user_id`, `created_time`),
    KEY `idx_ct_da` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT 'dashboard metrics';
