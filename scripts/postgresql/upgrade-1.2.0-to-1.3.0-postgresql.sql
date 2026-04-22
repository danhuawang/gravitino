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

-- Gravitino DB upgrade script: 1.2.0 → 1.3.0 (PostgreSQL)
-- Adds the license_nodes table for enterprise license node tracking.

CREATE TABLE IF NOT EXISTS license_nodes (
    node_id        VARCHAR(64)  NOT NULL,
    registered_at  BIGINT       NOT NULL,
    last_heartbeat BIGINT       NOT NULL,
    PRIMARY KEY (node_id)
);

COMMENT ON TABLE license_nodes IS 'tracks active Gravitino nodes for license enforcement';
COMMENT ON COLUMN license_nodes.node_id IS 'node identifier from gravitino.datastrato.license.nodeId';
COMMENT ON COLUMN license_nodes.registered_at IS 'epoch millis when node first registered';
COMMENT ON COLUMN license_nodes.last_heartbeat IS 'epoch millis of last heartbeat';
