<!--
Copyright 2024 Datastrato Inc.
-->

# Data Discovery in Gravitino Server

## Overview

Data Discovery provides a metadata search capability within the Gravitino server. It enables users to retrieve metadata of all kinds such as catalogs, schemas, tables, views, functions, filesets, topics, and models via API or web UI.

Searches can be performed across multiple fields including name, tags, and description. The search engine is built on **OpenSearch**, which offers powerful full-text search capabilities and scalability.

---

## Deployment & Configuration

Data Discovery uses **OpenSearch** as its indexing backend. OpenSearch can be deployed via Docker or consumed as a cloud service.

### Deploying OpenSearch via Docker

```bash
docker run -d -p 9200:9200 -p 9600:9600 \
  -e "discovery.type=single-node" \
  -e "OPENSEARCH_INITIAL_ADMIN_PASSWORD=axzin1S3?@A" \
  opensearchproject/opensearch:2.17.1
```

### Installing the IK Analyzer Plugin

```bash
docker exec -it opensearch-plugin install https://get.infini.cloud/opensearch/analysis-ik/2.17.1
```

### Gravitino Server Configuration

To enable Data Discovery, configure the Gravitino server with the following properties in the configuration file:

```properties
# Required configurations
gravitino.datastrato.search.storage.impl = opensearch
gravitino.datastrato.search.opensearch.url = https://localhost:9200
gravitino.datastrato.search.opensearch.username = admin
gravitino.datastrato.search.opensearch.password = axzin1S3?@A
gravitino.eventListener.names = search
gravitino.eventListener.search.class = com.datastrato.gravitino.search.listener.DataDiscoveryListener
```

> ⚠️ **Note**: Do not remove or modify the following required configuration items:
>
> ```properties
> gravitino.datastrato.search.storage.impl = opensearch
> gravitino.eventListener.names = search
> gravitino.eventListener.search.class = com.datastrato.gravitino.search.listener.DataDiscoveryListener
> ```

### Configuration Properties

The following table lists all configuration properties related to Data Discovery:

| Property                                                        | Description                               | Default    | Required | Since |
|-----------------------------------------------------------------|-------------------------------------------|------------|----------|-------|
| `gravitino.datastrato.search.storage.impl`                      | Search engine implementation              | opensearch | No       | 0.9.0 |
| `gravitino.datastrato.search.maxTaskQueueSize`                  | Max sync metadata tasks in sync queue     | 100        | No       | 0.9.0 |
| `gravitino.datastrato.search.maxBackgroundThreads`              | Max sync task threads for background sync | 10         | No       | 0.9.0 |
| `gravitino.datastrato.search.maxBackoffMs`                      | Max backoff time per batch (ms)           | 100        | No       | 0.9.0 |
| `gravitino.datastrato.search.syncBatchSize`                     | Number of records per one sync batch      | 100        | No       | 0.9.0 |
| `gravitino.datastrato.search.opensearch.url`                    | OpenSearch endpoint                       | -          | Yes      | 0.9.0 |
| `gravitino.datastrato.search.opensearch.username`               | OpenSearch username                       | -          | Yes      | 0.9.0 |
| `gravitino.datastrato.search.opensearch.password`               | OpenSearch password                       | -          | Yes      | 0.9.0 |
| `gravitino.datastrato.search.opensearch.writeMaxRetry`          | Max write retries                         | 3          | No       | 0.9.0 |
| `gravitino.datastrato.search.opensearch.retryBackoffMs`         | Retry backoff time (ms)                   | 3000       | No       | 0.9.0 |
| `gravitino.datastrato.search.opensearch.queryTimeoutMs`         | Query timeout (ms)                        | 30000      | No       | 0.9.0 |
| `gravitino.datastrato.search.opensearch.maxQueryThread`         | Max query threads                         | 10         | No       | 0.9.0 |
| `gravitino.datastrato.search.opensearch.maxQueryQueueSize`      | Max query queue size                      | 100        | No       | 0.9.0 |
| `gravitino.datastrato.search.opensearch.backgroundQueryTimeout` | Background query timeout                  | 2m         | No       | 0.9.0 |

## Index Mapping

Gravitino Data Discovery uses the following index mapping in OpenSearch, All entity types share a common structure with some entity-specific fields.

### Common Fields

| Field Path                                                                        | Type             |
|-----------------------------------------------------------------------------------|------------------|
| `entity_id`                                                                       | `long`           |
| `entity_type`                                                                     | `keyword`        |
| `in_use`                                                                          | `boolean`        |
| `metalake`                                                                        | `keyword`        |
| `owner`                                                                           | `keyword`        |
| `provider`                                                                        | `keyword`        |
| `entity_name`, `entity_name.keyword`, `entity_name.ngram`                         | `text / keyword` |
| `entity_comment`, `entity_comment.ngram`                                          | `text`           |
| `catalog_name`, `catalog_name.keyword`                                            | `text / keyword` |
| `full_qualified_name`, `full_qualified_name.keyword`, `full_qualified_name.ngram` | `text / keyword` |
| `tags` (nested)                                                                   | -                |
| `tags.tag_name`, `tags.tag_name.keyword`, `tags.tag_name.ngram`                   | `text / keyword` |
| `tags.tag_comment`                                                                | `text`           |
| `tags.properties`                                                                 | `object`         |
| `policy_names`, `policy_names.keyword`, `policy_names.ngram`                      | `text / keyword` |
| `search_audit.create_time`, `search_audit.last_modified_time`                     | `date`           |
| `search_audit.creator`, `search_audit.last_modifier`                              | `keyword`        |
| `user_permissions`, `role_permissions` (nested)                                   | -                |
| `entity_properties` (nested)                                                      | -                |
| `update_time`                                                                     | `long`           |

### Entity-Specific Fields

#### Table

| Field Path                                                                        | Type             |
|-----------------------------------------------------------------------------------|------------------|
| `columns.column_name`, `columns.column_name.keyword`, `columns.column_name.ngram` | `text / keyword` |
| `columns.column_comment`, `columns.column_comment.ngram`                          | `text`           |

#### View

| Field Path                                                                        | Type             |
|-----------------------------------------------------------------------------------|------------------|
| `columns.column_name`, `columns.column_name.keyword`, `columns.column_name.ngram` | `text / keyword` |
| `columns.column_comment`, `columns.column_comment.ngram`                          | `text`           |

The view definition itself (its SQL representations and the default catalog/schema used to resolve
it) is deliberately not indexed. Search does not match on it, and the Gravitino view API is the
place to read it, where the caller's view visibility is enforced.

The view mapping was introduced in the `v2` bundle and does not index the `in_use`, `owner`,
`user_permissions` and `role_permissions` common fields, nor `tags.tag_comment` and
`tags.properties`. Those values are still stored and returned with the document; they simply
cannot be searched or filtered on for views yet.

#### Function

The Function mapping was introduced in the `v2` bundle as a lightweight search projection. It
indexes the common name, comment, catalog, fully qualified name, tag name, and audit fields listed
above. Function definitions and parameters are deliberately not indexed; callers should use the
Gravitino Function API to retrieve those details after discovering a Function.

Like the View mapping, the Function mapping does not index the `in_use`, `owner`,
`user_permissions`, and `role_permissions` common fields, nor `tags.tag_comment` and
`tags.properties`.

#### Model

| Field Path                                                                                 | Type             |
|--------------------------------------------------------------------------------------------|------------------|
| `latest_version`                                                                           | `long`           |
| `model_versions` (nested)                                                                  | -                |
| `model_versions.version`                                                                   | `long`           |
| `model_versions.aliases`, `model_versions.aliases.keyword`, `model_versions.aliases.ngram` | `text / keyword` |
| `model_versions.uri`                                                                       | `keyword`        |

#### Policy

| Field Path                                 | Type             |
|--------------------------------------------|------------------|
| `policy_type`                              | `keyword`        |
| `enabled`                                  | `boolean`        |
| `content`, `content.ngram`                 | `text`           |


## Metadata Search API

Gravitino provides a RESTful API for metadata search. The API supports fuzzy search across multiple fields including name, tags, and description.

**Endpoint**

```http
GET /api/search/query
```

**Query Parameters**

| Name       | Type   | Required | Description                   | Example |
|------------|--------|----------|-------------------------------|---------|
| metalake   | string | Yes      | The name of the Metalake      | `test`  |
| keyword    | string | No       | Fuzzy search keyword          | `""`    |
| pageNumber | int    | Yes      | Page number (starting from 0) | `0`     |
| pageSize   | int    | Yes      | Number of results per page    | `10`    |

**Example**

```bash
# Search for metadata in the metalake `test` with keyword "demo", returning the first 10 results
curl -X GET "http://localhost:8090/api/search/query?metalake=test&keyword=demo&pageNumber=0&pageSize=10" | jq

# Search for metadata in the metalake `test` with no keyword, returning the first 10 results
curl -X GET "http://localhost:8090/api/search/query?metalake=test&pageNumber=0&pageSize=10" | jq

# Current we support filtering by catalog name, entity type, and tag name
# Search for metadata in the metalake `test` with keyword "demo" and filter: catalog_name=test_catalog, returning the first 10 results
curl -X GET "http://localhost:8090/api/search/query?metalake=test&keyword=demo%20catalog_name:test_catalog&pageNumber=0&pageSize=10" | jq

# Search for metadata in the metalake `test` with keyword "demo" and filter: entity_type=TABLE, returning the first 10 results
# Support entity type: CATALOG, SCHEMA, TABLE, VIEW, FUNCTION, MODEL, TOPIC, FILESET, TAG, POLICY
curl -X GET "http://localhost:8090/api/search/query?metalake=test&keyword=demo%20entity_type:TABLE&pageNumber=0&pageSize=10" | jq

# Search for metadata in the metalake `test` with keyword "demo" and filter: tag_name=demo_tag, returning the first 10 results
curl -X GET "http://localhost:8090/api/search/query?metalake=test&keyword=demo%20tag_name:demo_tag&pageNumber=0&pageSize=10" | jq

# Search for metadata in the metalake `test` with keyword "demo" and filter: catalog_name=test_catalog, entity_type=TABLE, tag_name=demo_tag, returning the first 10 results
curl -X GET "http://localhost:8090/api/search/query?metalake=test&keyword=demo%20catalog_name:test_catalog%20entity_type:TABLE%20tag_name:demo_tag&pageNumber=0&pageSize=10" | jq
```

For more advanced search capabilities, you can refer to the syntax of value in filter query parameters location at ${GRAVITINO_SEARCH_MODULE}/src/main/antlr/SearchExpression.g4, which supports logical operators and wildcards.


**Sample Response**

```json
{
  "code": 0,
  "entities": [
    {
      "totalSize": 1,
      "type": "catalog",
      "entities": [
        {
          "entityId": 969654441183647900,
          "entityType": "catalog",
          ...
        }
      ]
    },
    ...
  ]
}
```

The response contains a list of entities grouped by entity type, with each entity containing its metadata.

## Index Management

Gravitino creates a separate index for each entity type within every Metalake, using predefined index templates. When users upgrade index templates or need to fix index issues, a new version of the index is created to replace the old one. Queries use index aliases, which automatically route to the correct index based on the entity type.

The index template is named as follows:

```
{type}_entity_index_template_{version}
```

The index alias is named as follows:

```
{metalake_name}_{type}_entity_index
```

Then indexes is named as follows:

```text
{metalake_name}_{type}_entity_index_{version}
```

The example index information for the `test` Metalake are:

```text
| Alias                     | index name                            | Template name                    | Description                                 |
|---------------------------|---------------------------------------|----------------------------------|---------------------------------------------|
| test_catalog_entity_index | test_catalog_entity_index_11828332843 | catalog_entity_index_template_v2 | store the catalog metadata in test metalake |
| test_schema_entity_index  | test_schema_entity_index_11828332843  | schema_entity_index_template_v2  | store the schema metadata in test metalake  |
| test_table_entity_index   | test_table_entity_index_11828332843   | table_entity_index_template_v2   | store the table metadata in test metalake   |
| test_view_entity_index    | test_view_entity_index_11828332843    | view_entity_index_template_v2    | store the view metadata in test metalake    |
| test_function_entity_index | test_function_entity_index_11828332843 | function_entity_index_template_v2 | store the function metadata in test metalake |
| test_model_entity_index   | test_model_entity_index_11828332843   | model_entity_index_template_v2   | store the model metadata in test metalake   |
| test_topic_entity_index   | test_topic_entity_index_11828332843   | topic_entity_index_template_v2   | store the topic metadata in test metalake   |
| test_fileset_entity_index | test_fileset_entity_index_11828332843 | fileset_entity_index_template_v2 | store the fileset metadata in test metalake |
| test_tag_entity_index     | test_tag_entity_index_11828332843     | tag_entity_index_template_v2     | store the tag metadata in test metalake     |
| test_policy_entity_index  | test_policy_entity_index_11828332843  | policy_entity_index_template_v2  | store the policy metadata in test metalake  |

Each entity type has its own index template, which defines the mapping and settings for that index. The index templates are versioned to allow for upgrades and changes over time.

### Index Templates
Index template files are stored in:

```text
${PROJECT_GRAVITINO}/bin/opensearch
```
When we package the Gravitino server, the index templates are copied to:

```text
${GRAVITINO_HOME}/bin/opensearch
```

Each version contains the following:
```text
v1/
  ├── catalog_entity_indices.json
  ├── schema_entity_indices.json
  ├── table_entity_indices.json
  ├──  model_entity_indices.json 
  ├── topic_entity_indices.json
  ├── fileset_entity_indices.json
v2/
  ├── catalog_entity_indices.json
  ├── view_entity_indices.json
  ├── function_entity_indices.json
  ├── tag_entity_indices.json
  ├── policy_entity_indices.json
  ├── ...
```

If we are going to upgrade the index definitions, we can create a new version directory (e.g., `v3/`) with updated templates.
See [Set Up and Upgrade OpenSearch Index Templates](./opensearch-index-template-upgrade.md) for the
steps for a new install, an upgrade, a rebuild, and a normal restart.

## Metadata Synchronization

Gravitino server automatically maintains index synchronization using its event mechanism.

Initial Data Sync
- New deployments: Metadata is synced automatically.
- Existing deployments: Initial sync must be manually triggered via the API or CLI.

**Sync API**

When you want to manually trigger the Gravitino server metadata sync, you can use the following API:

```http
POST /api/search/sync/metalakes/{metalake}/objects
{
    "metadataFullName": "xxx",
    "metadataType": "xxx"
    "cascade": true
}
```

**Parameters**

| Name             | Type    | Required | Description                                                                                                                         | Example        |
|------------------|---------|----------|-------------------------------------------------------------------------------------------------------------------------------------|----------------|
| metalake         | string  | Yes      | Metalake name                                                                                                                       | `test`         |
| metadataFullName | string  | No       | Full name of the metadata entity, like <catalog>.<schema>.<table>. If `metadataFullName` is set, `metadataType` should also be set. | `test_catalog` |
| metadataType     | string  | No       | Type of metadata entity (e.g., `catalog`, `schema`, `table`, `view`, `function`, `model`, `topic`, `fileset`, `tag`, `policy`)                | `catalog`      |
| cascade          | boolean | No       | Whether to cascade sync to related entities                                                                                         | `true`         |

You can also post an empty body, then the Gravitino server will sync all metadata entities in the specified metalake.

**Example**

```bash
# sync all metadata entities in the `test` Metalake
curl -X POST -H "Content-Type: application/json" http://127.0.0.1:8090/api/search/sync/metalakes/test/objects
```

## Command-Line Index Management

The Gravitino server provides a CLI tool for OpenSearch templates and indexes.

```bash
gravitino-server$ bin/index.sh

Usage: bin/index.sh {init|upgrade|delete|version|rebuild|show} [version] [options]
```

### Options

| Option               | Description          |
|----------------------|----------------------|
| `--opensearch_uri`   | OpenSearch URI       |
| `--username`         | OpenSearch username  |
| `--password`         | OpenSearch password  |
| `--gravitino_uri`    | Gravitino server URI |
| `--help`             | Show help message    |

If no options are provided, the script will use the configuration from
`GRAVITINO_HOME/conf/gravitino.conf`.

### Supported Commands

| Command             | Description                                      |
|---------------------|--------------------------------------------------|
| `init [version]`    | Create templates for a new install               |
| `upgrade <version>` | Create new templates for an existing install     |
| `delete <version>`  | Delete the templates for one version             |
| `version`           | Show the version of each template                |
| `rebuild`           | Create new indexes and move aliases to them      |
| `show`              | Show aliases, indexes, and their templates       |

Use `init` only when no Gravitino template exists. Use `upgrade` when templates already exist.
These commands do not change old indexes. After a version upgrade, start the new Gravitino server
and run `rebuild`.

`bin/gravitino.sh start` tries to run `init`. `bin/gravitino.sh run` does not. The Docker entrypoint
uses `run`, so Docker and Kubernetes installs must create the templates before the Gravitino
container starts. Read
[Set Up and Upgrade OpenSearch Index Templates](./opensearch-index-template-upgrade.md) before you
choose a command.

### Check the current setup

```text
# show current index version
gravitino-server$ bin/index.sh version

Index version on OpenSearch:
table_entity_index v2
schema_entity_index v2
model_entity_index v2
catalog_entity_index v2
topic_entity_index v2
fileset_entity_index v2
tag_entity_index v2
policy_entity_index v2


# show index alias index and index template details
gravitino-server$ bin/index.sh show

Index alias on OpenSearch:
Alias    Index    Template
test_model_entity_index    test_model_entity_index_1754536385698    model_entity_index_template_v2
...

Index on OpenSearch:
health status index ... docs.count ...
yellow open test_model_entity_index_1754536385698 ... 3 ...
...
```
