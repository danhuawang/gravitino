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

cd "$(dirname "$0")"

# init_indices.sh
# Creates OpenSearch index templates based on JSON schema definitions
# Usage: ./create_indices_template.sh [version] [--uri=OPENSEARCH_URI] [--username=USERNAME] [--password=PASSWORD]
# Example: ./create_indices_template.sh v1 --uri=http://localhost:9200 --username=admin --password=secret

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

# Define JSON directory based on version
JSON_DIR="./opensearch/$VERSION"

# Check if version directory exists
if [ ! -d "$JSON_DIR" ]; then
    echo "Error: Directory $JSON_DIR does not exist"
    exit 1
fi

# Template mapping with wildcard patterns
KEYS=(
    "table_entity_index"
    "model_entity_index"
    "catalog_entity_index"
    "schema_entity_index"
    "topic_entity_index"
    "fileset_entity_index"
)

VALUES=(
    "table_entity_indices.json"
    "model_entity_indices.json"
    "catalog_entity_indices.json"
    "schema_entity_indices.json"
    "topic_entity_indices.json"
    "fileset_entity_indices.json"
)


# Function to create composable index template
create_composable_template() {
    local pattern=$1
    local json_file=$2
    local json_path="$JSON_DIR/$json_file"
    local priority=$3

    # Check if JSON template file exists
    if [ ! -f "$json_path" ]; then
        echo "Error: Template file $json_path does not exist"
        return 1
    fi

    local template_name="${pattern}_template_$VERSION"

    # Check if template already exists
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
        -X GET "$OPENSEARCH_URL/_index_template/$template_name" \
        $CREDENTIALS)

    if [ "$RESPONSE" == "200" ]; then
        echo "Error: Index template $template_name already exists"
        return 1
    fi

    # Create the index template
    echo "Creating composable index template: $template_name using $json_file"

    # Build the full template JSON
    TEMP_JSON=$(mktemp)
    cat <<EOF > "$TEMP_JSON"
{
  "index_patterns": ["*${pattern}*"],
  "priority": ${priority},
  "template": $(cat "$json_path")
}
EOF
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$OPENSEARCH_URL/_index_template/$template_name" \
        $CREDENTIALS \
        -H "Content-Type: application/json" \
        --data-binary "@$TEMP_JSON")

    # Cleanup temp file
    rm -f "$TEMP_JSON"

    if [ "$RESPONSE" != "200" ] && [ "$RESPONSE" != "201" ]; then
        echo "Error: Failed to create index template (HTTP $RESPONSE)"
        return 1
    fi

    echo "Successfully created composable index template: $template_name"
    return 0
}

# Main processing
ERROR_OCCURRED=0

# Create composable templates for each pattern
priority=110
for i in "${!KEYS[@]}"; do
    pattern="${KEYS[$i]}"
    json_file="${VALUES[$i]}"
    if ! create_composable_template "$pattern" "$json_file" "$priority"; then
        ERROR_OCCURRED=1
    fi
    let "priority+=1"
done

echo "Index template creation completed with status: $ERROR_OCCURRED"
