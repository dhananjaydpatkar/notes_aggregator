#!/usr/bin/env bash
set -e

# =============================================================================
# Notes Aggregator — End-to-End API Smoke Test Script
# Tests all 3 endpoints in reference open mode (no authcn / authzn)
#
# Usage:
#   ./smoke-test.sh [API_BASE_URL]
#   Example: ./smoke-test.sh http://localhost:3000
# =============================================================================

BASE_URL="${1:-http://localhost:3000}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_PAYLOAD="$SCRIPT_DIR/sample-oncology-note.json"
PATIENT_ID="PAT-9082341"
TENANT_ID="HOSP-WEST"

echo "======================================================================="
echo " NOTES AGGREGATOR — SMOKE TEST SUITE"
echo " Target API: $BASE_URL"
echo "======================================================================="

# -----------------------------------------------------------------------------
# 1. Ingest Clinical Note (POST /api/v1/notes)
# -----------------------------------------------------------------------------
echo ""
echo "[TEST 1/5] Ingesting clinical note for patient $PATIENT_ID..."
INGEST_RESP=$(curl -s -X POST "$BASE_URL/api/v1/notes" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "Idempotency-Key: MOIS-TEST-KEY-001" \
  -d @"$SAMPLE_PAYLOAD")

echo "Response:"
echo "$INGEST_RESP" | jq . || echo "$INGEST_RESP"

# -----------------------------------------------------------------------------
# 2. Idempotency Check (POST with identical Idempotency-Key)
# -----------------------------------------------------------------------------
echo ""
echo "[TEST 2/5] Testing Idempotency deduplication with same key..."
DEDUPE_RESP=$(curl -s -X POST "$BASE_URL/api/v1/notes" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "Idempotency-Key: MOIS-TEST-KEY-001" \
  -d @"$SAMPLE_PAYLOAD")

echo "Response (Should return 200 OK with cached noteId):"
echo "$DEDUPE_RESP" | jq . || echo "$DEDUPE_RESP"

# -----------------------------------------------------------------------------
# 3. Query Patient Notes (GET /api/v1/patients/{patientId}/notes) -> Warm INDEX
# -----------------------------------------------------------------------------
echo ""
echo "[TEST 3/5] Querying notes for patient $PATIENT_ID (Expected: servedFrom=INDEX)..."
QUERY_RESP=$(curl -s -X GET "$BASE_URL/api/v1/patients/$PATIENT_ID/notes?page=1&limit=10" \
  -H "X-Tenant-Id: $TENANT_ID")

echo "Response:"
echo "$QUERY_RESP" | jq . || echo "$QUERY_RESP"

# -----------------------------------------------------------------------------
# 4. Evict Patient from Index (DELETE /api/v1/patients/{patientId}/index)
# -----------------------------------------------------------------------------
echo ""
echo "[TEST 4/5] Evicting patient $PATIENT_ID from warm index..."
EVICT_RESP=$(curl -s -X DELETE "$BASE_URL/api/v1/patients/$PATIENT_ID/index" \
  -H "X-Tenant-Id: $TENANT_ID")

echo "Response:"
echo "$EVICT_RESP" | jq . || echo "$EVICT_RESP"

# -----------------------------------------------------------------------------
# 5. Query Patient Notes -> Cold Lazy Rehydration (servedFrom=S3_REHYDRATED)
# -----------------------------------------------------------------------------
echo ""
echo "[TEST 5/5] Querying evicted patient (Expected: lazy rehydration from S3 -> servedFrom=S3_REHYDRATED)..."
REHYDRATE_RESP=$(curl -s -X GET "$BASE_URL/api/v1/patients/$PATIENT_ID/notes?page=1&limit=10" \
  -H "X-Tenant-Id: $TENANT_ID")

echo "Response:"
echo "$REHYDRATE_RESP" | jq . || echo "$REHYDRATE_RESP"

echo ""
echo "======================================================================="
echo " ALL SMOKE TESTS COMPLETED!"
echo "======================================================================="
