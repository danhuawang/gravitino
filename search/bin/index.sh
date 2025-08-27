#!/bin/bash
#
# Copyright 2024 Datastrato Pvt Ltd.
# This software is licensed under the Apache License version 2.
#

cd "$(dirname "$0")"
# Define paths to the scripts
CREATE_SCRIPT="opensearch/create_indices_template.sh"
DELETE_SCRIPT="opensearch/delete_indices_template.sh"

# Show usage information
show_usage() {
    cat <<EOF
Usage: $0 {init|upgrade|delete|version|rebuild|show} [version] [options]
Options:
  --opensearch_uri=OPENSEARCH_URI       OpenSearch connection URI
  --username=USERNAME                   OpenSearch username
  --password=PASSWORD                   OpenSearch password
  --gravitino_uri=GRAVITINO_URI         Gravitino server connection URI
  --help                                Show this help message
EOF
    exit 1
}

# Check if required scripts exist
check_required_scripts() {
    if [ ! -f "$CREATE_SCRIPT" ] || [ ! -f "$DELETE_SCRIPT" ]; then
        echo "Error: Required scripts not found in the current directory."
        exit 1
    fi
}

check_jq() {
    if ! command -v jq &> /dev/null; then
        echo "Error: jq is not installed. Please install jq to parse JSON responses."
        echo "  Ubuntu/Debian: sudo apt-get install jq"
        exit 1
    fi
}

# Load OpenSearch configuration from environment variable or config file
load_config() {
    if [ -z "$GRAVITINO_HOME" ] || [ ! -d "$GRAVITINO_HOME" ]; then
        script_dir="$(pwd)"
        if [ -f "$script_dir/gravitino.sh" ]; then
            GRAVITINO_HOME="$(dirname "$script_dir")"
            echo "[INFO] Inferred GRAVITINO_HOME as $GRAVITINO_HOME"
        fi
    fi

    if [ -n "$GRAVITINO_HOME" ] && [ -d "$GRAVITINO_HOME" ]; then
        local conf_file="$GRAVITINO_HOME/conf/gravitino.conf"
        if [ -f "$conf_file" ]; then
            SEARCH_STORAGE=$(grep '^gravitino.datastrato.search.storage.impl' "$conf_file" | awk -F '=' '{print $2}' | xargs)
            if [ $SEARCH_STORAGE != "opensearch" ]; then
                echo "Search storeage is not OpenSearch, ignore the command"
                exit 1;
            fi

            OPENSEARCH_URL=$(grep '^gravitino.datastrato.search.opensearch.url' "$conf_file" | awk -F '=' '{print $2}' | xargs)
            USERNAME=$(grep '^gravitino.datastrato.search.opensearch.username' "$conf_file" | awk -F '=' '{print $2}' | xargs)
            PASSWORD=$(grep '^gravitino.datastrato.search.opensearch.password' "$conf_file" | awk -F '=' '{print $2}' | xargs)
            GRAVITINO_URI="http://127.0.0.1:$(grep '^gravitino.server.webserver.httpPort' "$conf_file" | awk -F '=' '{print $2}' | xargs)"
            echo "[INFO] Loaded OpenSearch config from $conf_file"
        else
            echo "[WARN] Config file not found: $conf_file. Skipping OpenSearch config init."
        fi
    fi
}

# Parse command line arguments
parse_arguments() {
    for arg in "$@"; do
        case $arg in
            --opensearch_uri=*) OPENSEARCH_URL="${arg#*=}" ;;
            --username=*) USERNAME="${arg#*=}" ;;
            --password=*) PASSWORD="${arg#*=}" ;;
            --gravitino_uri=*) GRAVITINO_URI="${arg#*=}" ;;
            --help) show_usage ;;
            *) VERSION="$arg" ;;
        esac
    done
}

# Validate required parameters
validate_parameters() {
    if [ -z "$OPENSEARCH_URL" ] || [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
        echo "Error: Missing required parameters (opensearch_uri, username, or password). or GRAVITINO_HOME not set."
        show_usage
    fi

}

validate_version() {
    if [ -z "$VERSION" ]; then 
        echo "Error: Missing required parameter version."
        show_usage
    fi
}

# Test OpenSearch connection
test_connection() {
    local credentials="-u $USERNAME:$PASSWORD -k"
    echo "Testing OpenSearch connection to $OPENSEARCH_URL..."
    local response=$(curl -s -o /dev/null -w "%{http_code}" "$OPENSEARCH_URL" $credentials)
    if [ "$response" == "200" ]; then
        echo "OpenSearch is reachable."
    else
        echo "Error: Failed to connect to OpenSearch (HTTP $response)."
        exit 1
    fi
}

print_index_template_version() {
    echo "Index version on OpenSearch:"

    local response=$(curl -s -k -u "$USERNAME:$PASSWORD" "$OPENSEARCH_URL/_index_template")
    local http_code=$(curl -s -o /dev/null -w "%{http_code}" -k -u "$USERNAME:$PASSWORD" "$OPENSEARCH_URL/_index_template")

    if [ "$http_code" -ne 200 ]; then
        echo "Error: Failed to fetch index templates (HTTP $http_code)."
        return 1
    fi

    echo "$response" \
    | jq -r '.index_templates[].name' \
    | sed -E 's/_entity_index_template_([a-zA-Z0-9]+)$/_entity_index \1/' \
    || echo "Error: Failed to parse index template list."
}

print_index() {
  echo "Index alias on OpenSearch:"
  echo -e "Alias\tIndex\tTemplate"

  curl -s -k -u "$USERNAME:$PASSWORD" "$OPENSEARCH_URL/_cat/aliases?v" |
    grep "_entity_index" |
    awk '{print $1, $2}' |
    while read -r alias index; do
      response=$(curl -s -k -u "$USERNAME:$PASSWORD" "$OPENSEARCH_URL/_index_template")
      templates=$(echo "$response" | jq -c '.index_templates[]')

      matched_template=""

      while IFS= read -r template; do
        name=$(echo "$template" | jq -r '.name')
        patterns=$(echo "$template" | jq -r '.index_template.index_patterns[]')

        for pattern in $patterns; do
          regex="^${pattern//\*/.*}$"
          if [[ "$index" =~ $regex ]]; then
            matched_template="$name"
            break 2
          fi
        done
      done <<< "$templates"

      echo -e "$alias\t$index\t$matched_template"
    done | column -t -s $'\t'

  echo ""
  echo "Index on OpenSearch:"
  curl -s -k -u "$USERNAME:$PASSWORD" "$OPENSEARCH_URL/_cat/indices?v" | grep -E '^health|_entity_index_'
}

get_all_index_template_versions() {
    local response=$(curl -s -k -u "$USERNAME:$PASSWORD" "$OPENSEARCH_URL/_index_template")
    local http_code=$(curl -s -o /dev/null -w "%{http_code}" -k -u "$USERNAME:$PASSWORD" "$OPENSEARCH_URL/_index_template")

    if [ "$http_code" -ne 200 ]; then
        echo "Error: Failed to fetch index templates (HTTP $http_code)."
        return 1
    fi

    echo "$response" \
    | jq -r '.index_templates[].name' \
    | sed -En 's/.*_entity_index_template_([a-zA-Z0-9]+)$/\1/p' \
    | sort -u \
    || echo "Error: Failed to parse index template list."
}

rebuild_index() {
  if [ -z "$GRAVITINO_URI" ]; then
    echo "Error: Missing required parameter gravitino_uri."
    show_usage
    return 1
  fi

  metalakes=$(curl -s "$GRAVITINO_URI/api/metalakes" | jq -r '.metalakes[].name')
  if [ -z "$metalakes" ]; then
    echo "Error: No metalakes found or failed to fetch metalakes."
    return 1
  fi

  failed=0

  for name in $metalakes; do
    echo "Rebuilding search index for metalake: $name"
    response=$(curl -s -w "%{http_code}" -o /tmp/rebuild_result.json \
      -X POST -H "Content-Type: application/json" "$GRAVITINO_URI/api/search/rebuild/metalakes/$name")

    if [[ "$response" == 2* ]]; then
      echo "Success rebuild index for metalake $name"
    else
      echo "Failed to rebuild index for metalake $name (HTTP $response):"
      cat /tmp/rebuild_result.json
      ((failed++))
    fi
    echo
  done

  if [ "$failed" -gt 0 ]; then
    echo "Completed with $failed failure(s)."
    return 1
  else
    echo "All metalake indexes rebuilt successfully."
  fi
}

# Main logic
main() {
    if [ $# -lt 1 ]; then
        echo "Error: No operation specified (init/upgrade/delete/version/show/rebuild)"
        show_usage
        exit 1
    fi

    OPERATION="$1"
    shift

    check_jq
    check_required_scripts
    load_config
    parse_arguments "$@"

    case "$OPERATION" in
        init)
            shift
            validate_parameters
            test_connection

            existVersions=$(get_all_index_template_versions)
            if [ ! -z "$existVersions" ]; then
              echo "Info: Existing index templates found with versions: $existVersions"
              exit 1
            fi

            if [ -z "$VERSION" ]; then
              VERSION=$(ls -d opensearch/v[0-9]* 2>/dev/null | sort -t 'v' -k2,2n | tail -1 | sed 's#.*/##')
              if [ -z "$VERSION" ]; then
                echo "Error: No version specified and no version directories found in opensearch"
                exit 1
              fi
            fi


            bash "$CREATE_SCRIPT" $VERSION $OPENSEARCH_URL "$USERNAME" "$PASSWORD"
            ;;

        upgrade)
            shift
            validate_parameters
            validate_version
            test_connection
            existVersions=$(get_all_index_template_versions)

            if echo "$existVersions" | grep -qx "$VERSION"; then
              echo "Index version $VERSION already exists, no need to update."
            else
              echo "Creating index template with version $VERSION..."
              bash "$CREATE_SCRIPT" "$VERSION" "$OPENSEARCH_URL" "$USERNAME" "$PASSWORD"
              if [ $? -eq 0 ]; then
                echo "Deleting previous index templates with version $versions..."
                bash "$DELETE_SCRIPT" "$existVersions" "$OPENSEARCH_URL" "$USERNAME" "$PASSWORD"
              fi
            fi
            ;;

        delete)
            shift
            validate_parameters
            validate_version
            test_connection
            bash "$DELETE_SCRIPT" $VERSION $OPENSEARCH_URL "$USERNAME" "$PASSWORD"
            ;;

        version)
            shift
            validate_parameters
            test_connection
            echo ""
            print_index_template_version
            ;;

        rebuild)
            shift
            echo ""
            rebuild_index
            ;;

        show)
            shift
            validate_parameters
            test_connection
            echo ""
            print_index
            ;;

        *)
            show_usage
            ;;
    esac
}

main "$@"
