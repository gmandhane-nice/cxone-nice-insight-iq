#!/bin/bash
# Run the agentic-rca-sample with dev profile (real Snowflake + tunnel endpoints)
#
# Prerequisites:
#   1. SSM tunnels running (use tunnels.ps1 in PowerShell):
#      - OpenSearch: localhost:6380 → cxcv-opensearch.dev.wfosaas.internal.com:443
#      - Valkey:     localhost:6379 → clustercfg.dev-cxcv-ctact-valkey.ibtpaj.usw2.cache.amazonaws.com:6379
#   2. AWS credentials configured (for Bedrock access)
#
# Usage:
#   ./run-dev.sh          — start the app with dev profile
#   ./run-dev.sh seed     — seed demo data into Valkey + OpenSearch, then start

set -e

PROFILE="dev"

if [ "$1" = "seed" ]; then
    echo ">>> Starting with dev + seed profiles (will populate Valkey & OpenSearch)"
    PROFILE="dev,seed"
fi

echo ">>> Building..."
mvn -q package -DskipTests

echo ">>> Starting agentic-rca-sample with profile: $PROFILE"
echo ">>> Snowflake: cxone_na1_dev (read-only)"
echo ">>> OpenSearch: https://localhost:6380 (via SSM tunnel)"
echo ">>> Valkey: localhost:6379 (via SSM tunnel)"
echo ""
echo ">>> Open http://localhost:8080 in your browser"
echo ""

java -jar target/agentic-rca-sample-0.1.0.jar \
    --spring.profiles.active="$PROFILE"
