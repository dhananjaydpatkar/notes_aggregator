# Notes Aggregator — Source Code & Reference Implementation

> **Serverless Clinical Notes Ingestion, Indian & HIPAA PHI De-Identification, and Patient-Centric Retrieval**  
> Reference Implementation — Open Endpoints (No Authcn / Authzn required)

---

## 1. Project Overview & Architecture

This repository contains the production-ready reference prototype for the **Clinical Notes Aggregator System**.

```
Source Applications (MOIS, ROIS, Lab, Surgery)
       │
       ▼ [1] POST /api/v1/notes (Open)
┌─────────────────────────────────────────────────────────┐
│ notes-ingestion-handler (AWS Lambda / Java 21)          │
│ ├─ Idempotency check via DynamoDbIdempotencyStore       │
│ ├─ Inline PHI Masking via IndianAndHipaaPhiMasker       │
│ ├─ Warm Indexing via OpenSearchIndexClient              │
│ └─ Durable Storage via S3DocumentStore                  │
└─────────────────────────────────────────────────────────┘
       │                              │
       ▼ [Warm Projection]            ▼ [Source of Truth]
OpenSearch Index                   AWS S3 Tenant Bucket
(clinical-notes-v1)               (v1/tenants/../patients/..)
       ▲                              ▲
       │                              │
┌─────────────────────────────────────┴───────────────────┐
│ notes-query-handler (AWS Lambda / Java 21)              │
│ [2] GET /api/v1/patients/{patientId}/notes              │
│ ├─ Check OpenSearch (servedFrom: INDEX)                 │
│ └─ If cold -> lazy scan S3 + bulk re-index (S3_REHYDRATED)
└─────────────────────────────────────────────────────────┘
       ▲
┌─────────────────────────────────────────────────────────┐
│ notes-index-handler (AWS Lambda / Java 21)              │
│ [3] DELETE /api/v1/patients/{patientId}/index           │
│ └─ Evicts patient from warm index only (S3 intact)      │
└─────────────────────────────────────────────────────────┘
```

---

## 2. Module Directory Structure

```
source/
├── pom.xml                         # Master parent Maven POM
├── notes-common/                   # Domain models, pluggable ports & infrastructure adapters
│   ├── src/main/java/.../model/    # ClinicalNote, MaskedNote, NoteSummary, IngestionResponse, etc.
│   ├── src/main/java/.../port/     # PhiMaskingPort, IdempotencyPort, SearchIndexPort, DocumentStorePort
│   ├── src/main/java/.../adapter/  # OpenSearchIndexClient, S3DocumentStore, DynamoDbIdempotencyStore
│   └── src/main/java/.../util/     # JsonUtil, S3PathBuilder
│
├── notes-phi-masking-handler/      # In-process Indian & HIPAA Safe Harbor de-identification engine
│   └── IndianAndHipaaPhiMasker     # Masks Aadhaar, ABHA, PAN, +91 Mobiles, Pincodes, Names, SSN, Emails
│
├── notes-ingestion-handler/        # AWS Lambda handler for POST /api/v1/notes
├── notes-query-handler/            # AWS Lambda handler for GET /api/v1/patients/{patientId}/notes (Lazy Index)
├── notes-index-handler/            # AWS Lambda handler for DELETE /api/v1/patients/{patientId}/index (Eviction)
│
└── infra/                          # Infrastructure definitions & local testing
    ├── template.yaml               # AWS SAM template (Open HTTP API)
    ├── docker-compose.yaml         # Local dev: OpenSearch, DynamoDB Local, LocalStack S3
    ├── env.local.json              # SAM local environment configuration
    ├── sample-oncology-note.json   # Test clinical payload
    └── smoke-test.sh               # Automated curl smoke test script
```

---

## 3. Endpoints Specification (Open Reference Implementation)

### 1. Ingest Clinical Note
* **Endpoint**: `POST /api/v1/notes`
* **Headers**: `Content-Type: application/json`, `X-Tenant-Id: <tenant>` (optional, defaults to `DEFAULT_TENANT`), `Idempotency-Key: <key>` (optional)
* **Sample Response (`201 Created`)**:
  ```json
  {
    "status": "SUCCESS",
    "noteId": "NOTE-4f016335-eb99-4d69-a1b7-7e614bc449db",
    "tenantId": "HOSP-WEST",
    "patientId": "PAT-9082341",
    "indexedAt": "2026-08-25T10:30:00Z",
    "storageUri": "s3://clinical-notes-store/v1/tenants/HOSP-WEST/patients/PAT-9082341/2026/08/NOTE-4f016335.json"
  }
  ```

### 2. Query Patient Notes (Newest First + Lazy Rehydration)
* **Endpoint**: `GET /api/v1/patients/{patientId}/notes?page=1&limit=20`
* **Headers**: `X-Tenant-Id: <tenant>`
* **Sample Response (`200 OK`)**:
  ```json
  {
    "patientId": "PAT-9082341",
    "tenantId": "HOSP-WEST",
    "totalNotes": 1,
    "page": 1,
    "limit": 20,
    "servedFrom": "INDEX",
    "notes": [
      {
        "noteId": "NOTE-4f016335-eb99-4d69-a1b7-7e614bc449db",
        "noteType": "MEDICAL_ONCOLOGY_PROGRESS",
        "author": {
          "clinicianId": "DOC-7712",
          "name": "[PHI:CLINICIAN:a8f19c]",
          "department": "Medical Oncology"
        },
        "authoredAt": "2026-08-18T10:30:00Z",
        "recordedAt": "2026-08-18T10:32:15Z",
        "sourceSystem": "MOIS",
        "facilityId": "HOSP-WEST-01",
        "summary": "Stage III Colorectal Adenocarcinoma (cT3N1M0), tolerating oxaliplatin regimen reasonably well.",
        "storageUri": "/api/v1/notes/NOTE-4f016335-eb99-4d69-a1b7-7e614bc449db/document"
      }
    ]
  }
  ```

### 3. Evict Patient from Warm Index
* **Endpoint**: `DELETE /api/v1/patients/{patientId}/index`
* **Headers**: `X-Tenant-Id: <tenant>`
* **Sample Response (`200 OK`)**:
  ```json
  {
    "patientId": "PAT-9082341",
    "tenantId": "HOSP-WEST",
    "deletedFromIndex": 1,
    "dataRetainedInS3": true,
    "message": "Patient data evicted from warm index. Data intact in S3. Next read will rehydrate automatically."
  }
  ```

---

## 4. Building & Running Tests

### Prerequisites
* Java 21 LTS
* Maven 3.9+
* Docker & Docker Compose (for local testing)

### Run Unit Tests
```bash
cd source
mvn clean test
```

### Run Shaded Package (Generates Lambda Fat JARs)
```bash
cd source
mvn clean package -DskipTests
```

---

## 5. Local Development with Docker & SAM

```bash
# 1. Start local services (OpenSearch, DynamoDB Local, LocalStack S3)
cd source/infra
docker compose up -d

# 2. Start SAM local API gateway
sam local start-api --template template.yaml --env-vars env.local.json

# 3. Run automated smoke tests against localhost:3000
./smoke-test.sh http://localhost:3000
```
