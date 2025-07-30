#!/bin/bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# delete_templates.sh
# Deletes OpenSearch index templates created by init_indices.sh
# Usage: ./delete_indices_template.sh [version] [--uri=OPENSEARCH_URI] [--username=USERNAME] [--password=PASSWORD]
# Example: ./delete_indices_template.sh v1 --uri=http://localhost:9200 --username=admin --password=secret

# Show usage information
show_usage() {
    echo "Usage: $0 [version] [options]"
    echo "Options:"
    echo "  --uri=OPENSEARCH_URI        OpenSearch connection URI (required)"
    echo "  --username=USERNAME         OpenSearch username (required)"
    echo "  --password=PASSWORD         OpenSearch password (required)"
    echo "  --help                      Show this help message"
    echo ""
    echo "If options are not provided, will use environment variables:"
    echo "  OPEN_SEARCH_URI, OPEN_SEARCH_USERNAME, OPEN_SEARCH_PASSWORD (all required)"
    exit 1
}

# Parse command line arguments
VERSION=""
OPENSEARCH_URL=""
USERNAME=""
PASSWORD=""

if [ -n "$GRAVITINO_HOME" ] && [ -d "$GRAVITINO_HOME" ]; then
  CONF_FILE="$GRAVITINO_HOME/conf/gravitino.conf"

  if [ -f "$CONF_FILE" ]; then
    OPENSEARCH_URL=$(grep '^gravitino.datastrato.search.opensearch.url' "$CONF_FILE" | awk -F '=' '{print $2}' | xargs)
    USERNAME=$(grep '^gravitino.datastrato.search.opensearch.username' "$CONF_FILE" | awk -F '=' '{print $2}' | xargs)
    PASSWORD=$(grep '^gravitino.datastrato.search.opensearch.password' "$CONF_FILE" | awk -F '=' '{print $2}' | xargs)

    echo "[INFO] Loaded OpenSearch config from $CONF_FILE"
    echo "OPENSEARCH_URL=$OPENSEARCH_URL"
    echo "USERNAME=$USERNAME"
    echo "PASSWORD=$PASSWORD"
  else
    echo "[WARN] Config file not found: $CONF_FILE. Skipping OpenSearch config init."
  fi
fi

for arg in "$@"; do
    case $arg in
        --uri=*)
        OPENSEARCH_URL="${arg#*=}"
        shift
        ;;
        --username=*)
        USERNAME="${arg#*=}"
        shift
        ;;
        --password=*)
        PASSWORD="${arg#*=}"
        shift
        ;;
        --help)
        show_usage
        ;;
        -*)
        echo "Unknown option: $arg"
        show_usage
        ;;
        *)
        VERSION="$arg"
        ;;
    esac
done

# Validate version parameter
if [ -z "$VERSION" ]; then
    echo "Error: Version parameter is required"
    show_usage
fi

# Get values from environment variables if not provided via parameters
if [ -z "$OPENSEARCH_URL" ]; then
    if [ -n "$OPEN_SEARCH_URI" ]; then
        OPENSEARCH_URL="$OPEN_SEARCH_URI"
    else
        echo "Error: OpenSearch URI must be specified via --uri or OPEN_SEARCH_URI environment variable"
        show_usage
    fi
fi

if [ -z "$USERNAME" ]; then
    if [ -n "$OPEN_SEARCH_USERNAME" ]; then
        USERNAME="$OPEN_SEARCH_USERNAME"
    else
        echo "Error: OpenSearch username must be specified via --username or OPEN_SEARCH_USERNAME environment variable"
        show_usage
    fi
fi

if [ -z "$PASSWORD" ]; then
    if [ -n "$OPEN_SEARCH_PASSWORD" ]; then
        PASSWORD="$OPEN_SEARCH_PASSWORD"
    else
        echo "Error: OpenSearch password must be specified via --password or OPEN_SEARCH_PASSWORD environment variable"
        show_usage
    fi
fi

# Prepare credentials for curl
CREDENTIALS="-u $USERNAME:$PASSWORD -k"

# Test OpenSearch connection
echo "Testing OpenSearch connection to $OPENSEARCH_URL..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" $OPENSEARCH_URL $CREDENTIALS)

if [ "$RESPONSE" != "200" ]; then
    echo "Error: Failed to connect to OpenSearch (HTTP $RESPONSE)"
    exit 1
fi

echo "Successfully connected to OpenSearch"

# Define template mapping (must match init_indices.sh)
KEYS=(
    "table_entity_index"
    "model_entity_index"
    "catalog_entity_index"
    "schema_entity_index"
    "topic_entity_index"
    "fileset_entity_index"
)

VALUES=(
    "table_entity_index_template_$VERSION"
    "model_entity_index_template_$VERSION"
    "catalog_entity_index_template_$VERSION"
    "schema_entity_index_template_$VERSION"
    "topic_entity_index_template_$VERSION"
    "fileset_entity_index_template_$VERSION"
)

# Function to delete index template
delete_index_template() {
    local template_name=$1

    # Check if template exists
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
        -X GET "$OPENSEARCH_URL/_index_template/$template_name" \
        $CREDENTIALS)

    if [ "$RESPONSE" != "200" ]; then
        echo "Warning: Index template $template_name does not exist"
        return 0
    fi

    # Delete the index template
    echo "Deleting index template: $template_name"

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
        -X DELETE "$OPENSEARCH_URL/_index_template/$template_name" \
        $CREDENTIALS)

    if [ "$RESPONSE" != "200" ]; then
        echo "Error: Failed to delete index template (HTTP $RESPONSE)"
        return 1
    fi

    echo "Successfully deleted index template: $template_name"
    return 0
}

# Main processing
ERROR_OCCURRED=0

# Delete all templates defined in the mapping
for i in "${!VALUES[@]}"; do
    template_name="${VALUES[$i]}"
    if ! delete_index_template "$template_name"; then
           ERROR_OCCURRED=1
    fi
    let "priority+=1"
done



# Final status message
if [ "$ERROR_OCCURRED" -eq 0 ]; then
    echo ""
    echo "All index templates deleted successfully!"
    exit 0
else
    echo ""
    echo "Error: Some index templates could not be deleted"
    exit 1
fi
