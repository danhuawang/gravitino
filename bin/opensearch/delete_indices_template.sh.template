#!/bin/bash

#
# Copyright 2024 Datastrato Pvt Ltd.
# This software is licensed under the Apache License version 2.
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
