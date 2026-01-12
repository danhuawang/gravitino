/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

export const checkCatalogIcon = ({ type, provider }) => {
  switch (type) {
    case 'relational':
      switch (provider) {
        case 'hive':
          return 'custom-icons-hive'
        case 'lakehouse-iceberg':
          return 'openmoji:iceberg'
        case 'jdbc-mysql':
          return 'devicon:mysql-wordmark'
        case 'jdbc-bigquery':
          return 'simple-icons:googlebigquery'
        case 'jdbc-postgresql':
          return 'devicon:postgresql-wordmark'
        case 'jdbc-doris':
          return 'custom-icons-doris'
        case 'jdbc-starrocks':
          return 'custom-icons-starrocks'
        case 'lakehouse-paimon':
          return 'custom-icons-paimon'
        case 'lakehouse-hudi':
          return 'custom-icons-hudi'
        case 'lakehouse-generic':
          return 'material-symbols:houseboat-outline'
        case 'jdbc-oceanbase':
          return 'custom-icons-oceanbase'
        default:
          return 'bx:book'
      }
    case 'messaging':
      return 'skill-icons:kafka'
    case 'fileset':
      return 'twemoji:file-folder'
    case 'model':
      return 'carbon:machine-learning-model'
    default:
      return 'bx:book'
  }
}
