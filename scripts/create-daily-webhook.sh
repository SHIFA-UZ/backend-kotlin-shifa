#!/usr/bin/env bash
# Create Daily.co webhook for AI scribe (recording.ready-to-download).
# See: https://docs.daily.co/reference/rest-api/webhooks/create
#
# Usage:
#   export DAILY_API_KEY="your-daily-api-key"
#   ./scripts/create-daily-webhook.sh
#
# Or with inline key (replace YOUR_DAILY_API_KEY):
#   DAILY_API_KEY=YOUR_DAILY_API_KEY ./scripts/create-daily-webhook.sh

set -e

WEBHOOK_URL="${DAILY_WEBHOOK_URL:-https://shifa-doc-backend-mvp-production.up.railway.app/api/webhooks/daily}"
API_KEY="${DAILY_API_KEY:?Set DAILY_API_KEY (e.g. export DAILY_API_KEY=your-key)}"

echo "Creating webhook for: $WEBHOOK_URL"

response=$(curl -s -w "\n%{http_code}" -X POST "https://api.daily.co/v1/webhooks" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d "{
    \"url\": \"$WEBHOOK_URL\",
    \"eventTypes\": [\"recording.ready-to-download\"]
  }")

http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | sed '$d')

if [ "$http_code" = "200" ]; then
  echo "Webhook created successfully."
  echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
else
  echo "Failed (HTTP $http_code): $body"
  exit 1
fi
