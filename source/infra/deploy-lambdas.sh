#!/usr/bin/env bash
# Deploy updated shaded JARs to AWS Lambda functions
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
REGION="${AWS_REGION:-ap-south-1}"

echo "========================================================"
echo " Deploying Clinical Notes Aggregator Lambdas to ${REGION}"
echo "========================================================"

# Check AWS authentication
if ! aws sts get-caller-identity >/dev/null 2>&1; then
  echo "ERROR: AWS CLI is not authenticated or session has expired."
  echo "Please run 'aws login' or configure your credentials, then re-run this script."
  exit 1
fi

INGEST_JAR="${ROOT_DIR}/source/notes-ingestion-handler/target/notes-ingestion-handler-1.0.0-SNAPSHOT.jar"
QUERY_JAR="${ROOT_DIR}/source/notes-query-handler/target/notes-query-handler-1.0.0-SNAPSHOT.jar"

if [[ ! -f "${INGEST_JAR}" ]] || [[ ! -f "${QUERY_JAR}" ]]; then
  echo "Building shaded JARs with Maven..."
  mvn -f "${ROOT_DIR}/source/pom.xml" clean package -DskipTests
fi

echo "--> Updating notes-ingestion-handler..."
aws lambda update-function-code \
  --region "${REGION}" \
  --function-name notes-ingestion-handler \
  --zip-file "fileb://${INGEST_JAR}" \
  --output text --query 'FunctionArn'

echo "--> Updating notes-query-handler..."
aws lambda update-function-code \
  --region "${REGION}" \
  --function-name notes-query-handler \
  --zip-file "fileb://${QUERY_JAR}" \
  --output text --query 'FunctionArn'

echo "========================================================"
echo "✓ Both Lambda functions successfully updated!"
echo "========================================================"
