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

VERSION=$1
OPENSEARCH_URL=$2
USERNAME=$3
PASSWORD=$4

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
    echo "Error: The index tempalte Directory $JSON_DIR of version $VERSION does not exist"
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
        echo "Info: Index template $template_name already exists"
        return 0
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
NUM="${VERSION//[!0-9]/}"
priority=$((10000 + NUM * 10))
for i in "${!KEYS[@]}"; do
    pattern="${KEYS[$i]}"
    json_file="${VALUES[$i]}"
    if ! create_composable_template "$pattern" "$json_file" "$priority"; then
        ERROR_OCCURRED=1
    fi
    let "priority+=1"
done

echo "Index template creation completed with status: $ERROR_OCCURRED"
