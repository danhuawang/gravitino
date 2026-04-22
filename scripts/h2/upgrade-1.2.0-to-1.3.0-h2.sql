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

-- Gravitino DB upgrade script: 1.2.0 → 1.3.0 (H2)
-- Adds the license_nodes table for enterprise license node tracking.

CREATE TABLE IF NOT EXISTS `license_nodes` (
    `node_id`        VARCHAR(64)  NOT NULL COMMENT 'node identifier from gravitino.datastrato.license.nodeId',
    `registered_at`  BIGINT       NOT NULL COMMENT 'epoch millis when node first registered',
    `last_heartbeat` BIGINT       NOT NULL COMMENT 'epoch millis of last heartbeat',
    PRIMARY KEY (`node_id`)
) ENGINE=InnoDB COMMENT='tracks active Gravitino nodes for license enforcement';
