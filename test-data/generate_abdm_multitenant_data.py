#!/usr/bin/env python3
"""
Generate and Seed ABDM Multi-Tenant Cross-Hospital Clinical Notes Test Data.

Simulates a real-world ABDM scenario:
A cancer patient receives care across multiple independent hospital systems:
- TMH-MUMBAI (Tata Memorial Centre, Mumbai) - Surgery & Biopsy
- APOLLO-BLR (Apollo Hospitals, Bengaluru) - Systemic Chemotherapy & Labs
- AIIMS-DEL (AIIMS, New Delhi) - Radiation Oncology & Care Plan

The patient is unified across all hospitals via their ABDM ABHA ID:
  Patient 1: Anita Deshmukh -> ABHA: 14-8765-4321-9876 (PAT-ABDM-001)
  Patient 2: Vikram Malhotra -> ABHA: 14-1234-5678-9012 (PAT-ABDM-002)

Under standard single-tenant query:
  Each hospital only sees its own notes.

Under ABDM Consent Artefact query:
  The federated view combines all notes across hospitals into a single longitudinal timeline.
"""

import os
import json
import hashlib
import urllib.request
import urllib.error
from datetime import datetime, timezone

OUTPUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "abdm-multitenant")
os.makedirs(OUTPUT_DIR, exist_ok=True)

def hash_phi(val: str, length: int = 6) -> str:
    """Deterministic SHA-256 hash matching IndianAndHipaaPhiMasker."""
    h = hashlib.sha256(val.strip().encode("utf-8")).hexdigest()
    return h[:length]

def create_note(note_id, tenant_id, patient_id, abha_id, name, age, gender,
                note_type, title, clinician_id, doc_name, dept,
                authored_at, raw_text, summary, facility_id, source_system):
    """Generates both raw clinical note and masked indexable note."""
    
    # Raw Note (for ingestion API POST)
    raw_note = {
        "noteId": note_id,
        "tenantId": tenant_id,
        "patientId": patient_id,
        "encounterId": f"ENC-{note_id[-8:]}",
        "sourceSystem": source_system,
        "facilityId": facility_id,
        "noteType": note_type,
        "author": {
            "clinicianId": clinician_id,
            "name": doc_name,
            "department": dept
        },
        "demographics": {
            "age": age,
            "gender": gender,
            "abhaId": abha_id,
            "city": "Mumbai" if "MUMBAI" in tenant_id else ("Bengaluru" if "BLR" in tenant_id else "New Delhi")
        },
        "timestamps": {
            "authoredAt": authored_at,
            "recordedAt": authored_at
        },
        "content": {
            "format": "SECTIONS",
            "title": title,
            "rawText": raw_text,
            "sections": {
                "clinicalSummary": summary,
                "impression": summary
            }
        },
        "summary": summary
    }

    # Masked Note (as stored in OpenSearch & S3)
    masked_abha = f"[PHI:ABHA:{hash_phi(abha_id)}]"
    masked_name = f"[PHI:NAME:{hash_phi(name)}]"
    masked_text = raw_text.replace(name, masked_name).replace(abha_id, masked_abha)

    masked_note = {
        "noteId": note_id,
        "tenantId": tenant_id,
        "patientId": patient_id,
        "encounterId": raw_note["encounterId"],
        "sourceSystem": source_system,
        "facilityId": facility_id,
        "noteType": note_type,
        "author": raw_note["author"],
        "demographics": {
            "age": age,
            "gender": gender,
            "abhaId": masked_abha,
            "city": raw_note["demographics"]["city"]
        },
        "timestamps": raw_note["timestamps"],
        "content": {
            "format": "SECTIONS",
            "title": title,
            "rawText": masked_text,
            "sections": raw_note["content"]["sections"]
        },
        "summary": summary,
        "maskingMetadata": {
            "maskedAt": datetime.now(timezone.utc).isoformat(),
            "tokensMaskedCount": 2,
            "maskingEngine": "INDIAN_HIPAA_NER_V1"
        }
    }

    return raw_note, masked_note

# ═════════════════════════════════════════════════════════════════════════════
# Patient 1: Anita Deshmukh (Stage II Breast Ca)
# Cross-hospital journey: TMH (Surgery/Biopsy) -> Apollo (Chemo) -> AIIMS (Radiation)
# ═════════════════════════════════════════════════════════════════════════════
P1_RAW_NOTES = []
P1_MASKED_NOTES = []

notes_p1_defs = [
    # 1. TMH Mumbai - Surgical Operative Note
    (
        "NOTE-TMH-BRCA-001", "TMH-MUMBAI", "PAT-ABDM-001", "14-8765-4321-9876", "Anita Deshmukh", 52, "F",
        "OPERATIVE_NOTE", "Right Modified Radical Mastectomy & Axillary Dissection",
        "DOC-TMH-7101", "Dr. Sunil Deshmukh, MS, MCh", "Surgical Oncology",
        "2026-03-12T09:30:00Z",
        "Patient Anita Deshmukh (ABHA: 14-8765-4321-9876) underwent right modified radical mastectomy under GA. Level I and II axillary lymph nodes cleared. Hemostasis secured. Two closed suction drains placed. Patient transferred to PACU stable.",
        "Right Modified Radical Mastectomy + Axillary Clearance. Uneventful procedure, margins gross clear.",
        "FAC-TMH-MAIN-OT", "TMH-EHR-V2"
    ),
    # 2. TMH Mumbai - Histopathology Report
    (
        "NOTE-TMH-PATH-002", "TMH-MUMBAI", "PAT-ABDM-001", "14-8765-4321-9876", "Anita Deshmukh", 52, "F",
        "PATHOLOGY_REPORT", "Histopathology: Right Breast & Axillary Contents",
        "DOC-TMH-7401", "Dr. Meenakshi Sundaram, MD", "Oncopathology",
        "2026-03-18T14:15:00Z",
        "Specimen: Right mastectomy specimen. Tumor size: 2.8 x 2.2 cm. Histology: Invasive Ductal Carcinoma, Nottingham Grade 2. Resection margins: All margins negative (>5mm). Axillary nodes: 1/14 positive for metastasis. IHC: ER positive (80%), PR positive (65%), HER2/neu: Negative (1+). Ki-67: 22%. Pathologic Stage: pT2 N1a M0.",
        "Invasive Ductal Carcinoma Grade 2 (pT2N1aM0). ER+/PR+, HER2 negative. 1/14 nodes involved.",
        "FAC-TMH-PATH", "TMH-LIS"
    ),
    # 3. Apollo Bengaluru - Chemotherapy Progress Note
    (
        "NOTE-APL-CHM-003", "APOLLO-BLR", "PAT-ABDM-001", "14-8765-4321-9876", "Anita Deshmukh", 52, "F",
        "MEDICAL_ONCOLOGY_PROGRESS", "Adjuvant Chemotherapy: AC-T Regimen Cycle 3",
        "DOC-APL-7201", "Dr. Sarah Jenkins, MD, DM", "Medical Oncology",
        "2026-05-10T11:00:00Z",
        "Patient presented for Cycle 3 of AC chemotherapy (Doxorubicin 60 mg/m2 + Cyclophosphamide 600 mg/m2). Performance status ECOG 1. Mild nausea controlled with Aprepitant. No mucositis, neuropathy, or chest discomfort. Chemotherapy infused without acute hypersensitivity.",
        "Completed Cycle 3 AC Chemotherapy for breast carcinoma. Good tolerance, ECOG 1.",
        "FAC-APL-DAYCARE", "APOLLO-MEDSYS"
    ),
    # 4. Apollo Bengaluru - Lab Hematology Report
    (
        "NOTE-APL-LAB-004", "APOLLO-BLR", "PAT-ABDM-001", "14-8765-4321-9876", "Anita Deshmukh", 52, "F",
        "LAB_REPORT", "Routine Pre-Chemo Complete Blood Count & Renal Panel",
        "DOC-APL-7402", "Dr. Arvind Swaminathan, MD", "Clinical Pathology",
        "2026-05-09T08:45:00Z",
        "Hb: 11.4 g/dL, WBC: 4,800/uL, Absolute Neutrophil Count (ANC): 2,450/uL, Platelets: 185,000/uL. Serum Creatinine: 0.82 mg/dL, SGOT: 28 U/L, SGPT: 31 U/L. Bone marrow and organ functions adequate for systemic cytotoxic therapy.",
        "Hematology & Biochemistry satisfactory. ANC 2,450/uL, cleared for cytotoxic infusion.",
        "FAC-APL-LAB", "APOLLO-LIS"
    ),
    # 5. AIIMS New Delhi - Radiation Oncology Treatment Note
    (
        "NOTE-AIM-RAD-005", "AIIMS-DEL", "PAT-ABDM-001", "14-8765-4321-9876", "Anita Deshmukh", 52, "F",
        "RADIATION_ONCOLOGY_NOTE", "Adjuvant Radiotherapy Completion: 3D-CRT Chest Wall + SCF",
        "DOC-AIM-7301", "Dr. Sanjay Bhatt, MD, DNB", "Radiation Oncology",
        "2026-08-04T15:20:00Z",
        "Patient completed 40 Gy in 15 fractions external beam radiotherapy to the right chest wall and supraclavicular fossa using 6 MV photons. Acute skin reaction: RTOG Grade 1 erythema. Recommended topical moisturizers. Heart and contralateral breast doses well within constraints.",
        "Completed 40 Gy/15# adjuvant EBRT to chest wall & SCF. Grade 1 skin reaction, no pneumonitis.",
        "FAC-AIM-LINAC2", "AIIMS-RADNET"
    ),
    # 6. AIIMS New Delhi - Survivorship & Treatment Care Plan
    (
        "NOTE-AIM-PLN-006", "AIIMS-DEL", "PAT-ABDM-001", "14-8765-4321-9876", "Anita Deshmukh", 52, "F",
        "TREATMENT_PLAN", "Comprehensive Oncology Survivorship & Endocrine Therapy Plan",
        "DOC-AIM-7202", "Dr. Rajiv Singhania, MD, ECMO", "Medical Oncology",
        "2026-08-20T10:30:00Z",
        "Multimodal management concluded (Surgery at TMH, Chemotherapy at Apollo, Radiotherapy at AIIMS). Patient is in complete clinical and radiological remission. Initiating adjuvant endocrine therapy: Tab Tamoxifen 20 mg once daily for 5-10 years with annual gynecological evaluation. Routine surveillance every 3 months.",
        "Complete remission post multimodal therapy across TMH/Apollo/AIIMS. Initiated Tamoxifen 20mg daily.",
        "FAC-AIM-OPD", "AIIMS-EHR-V3"
    )
]

for item in notes_p1_defs:
    raw, masked = create_note(*item)
    P1_RAW_NOTES.append(raw)
    P1_MASKED_NOTES.append(masked)

# ═════════════════════════════════════════════════════════════════════════════
# Patient 2: Vikram Malhotra (Stage IIA Lung Adenocarcinoma)
# Cross-hospital journey: Max Delhi (Surgery) -> Fortis Gurugram (Targeted Therapy)
# ═════════════════════════════════════════════════════════════════════════════
P2_RAW_NOTES = []
P2_MASKED_NOTES = []

notes_p2_defs = [
    (
        "NOTE-MAX-SURG-001", "MAX-DELHI", "PAT-ABDM-002", "14-1234-5678-9012", "Vikram Malhotra", 64, "M",
        "OPERATIVE_NOTE", "Video-Assisted Thoracoscopic (VATS) Right Upper Lobectomy",
        "DOC-MAX-7105", "Dr. Rajeshwar Sharma, MCh", "Thoracic Surgical Oncology",
        "2026-04-05T08:00:00Z",
        "Patient Vikram Malhotra (ABHA: 14-1234-5678-9012) underwent uneventful VATS right upper lobectomy and radical systemic mediastinal lymphadenectomy (stations 2R, 4R, 7, 10R). Chest tube placed on -20 cmH2O suction.",
        "VATS Right Upper Lobectomy + Mediastinal Node Dissection. Uneventful procedure.",
        "FAC-MAX-OT3", "MAX-HIS"
    ),
    (
        "NOTE-MAX-PATH-002", "MAX-DELHI", "PAT-ABDM-002", "14-1234-5678-9012", "Vikram Malhotra", 64, "M",
        "PATHOLOGY_REPORT", "Molecular & Genomic Pathology: Lung Adenocarcinoma",
        "DOC-MAX-7408", "Dr. Priya Bansal, MD", "Molecular Pathology",
        "2026-04-12T16:00:00Z",
        "Invasive adenocarcinoma of right upper lobe, acinar predominant. Stage: pT2a N0 M0 (Stage IB). Molecular NGS Panel: EGFR Exon 21 L858R mutation DETECTED. ALK and ROS1 negative. PD-L1 TPS: 15%. Targetable EGFR mutation present.",
        "Lung Adenocarcinoma pT2aN0M0 with EGFR L858R sensitizing mutation detected.",
        "FAC-MAX-GENOMICS", "MAX-LIS"
    ),
    (
        "NOTE-FRT-ONC-003", "FORTIS-NCR", "PAT-ABDM-002", "14-1234-5678-9012", "Vikram Malhotra", 64, "M",
        "MEDICAL_ONCOLOGY_PROGRESS", "Adjuvant Targeted Therapy: Osimertinib Initiation",
        "DOC-FRT-7204", "Dr. Vinay Goel, DM", "Medical Oncology",
        "2026-05-02T11:30:00Z",
        "Review of VATS resection and molecular report from Max Delhi confirming EGFR L858R mutation. Discussed ADAURA trial data. Initiated adjuvant Osimertinib 80 mg orally once daily. Baseline ECG QTc normal (410 ms). Advised regarding rash, diarrhea, and paronychia management.",
        "Commenced Adjuvant Osimertinib 80mg OD based on Max Delhi pathology EGFR L858R.",
        "FAC-FRT-OPD", "FORTIS-CARE"
    ),
    (
        "NOTE-FRT-RAD-004", "FORTIS-NCR", "PAT-ABDM-002", "14-1234-5678-9012", "Vikram Malhotra", 64, "M",
        "RADIOLOGY_REPORT", "Surveillance Contrast-Enhanced Chest CT",
        "DOC-FRT-7502", "Dr. Neha Agnihotri, DMRD", "Radiodiagnosis",
        "2026-08-15T14:30:00Z",
        "CT Chest: Status post right upper lobectomy. Clean bronchial stump. No evidence of recurrent local soft tissue mass or mediastinal lymphadenopathy. Clear left lung parenchyma. Stable post-surgical appearance.",
        "No evidence of local recurrence or distant pulmonary metastasis on post-op CT.",
        "FAC-FRT-CT1", "FORTIS-PACS"
    )
]

for item in notes_p2_defs:
    raw, masked = create_note(*item)
    P2_RAW_NOTES.append(raw)
    P2_MASKED_NOTES.append(masked)

# ═════════════════════════════════════════════════════════════════════════════
# ABDM Consent Artefact Model
# ═════════════════════════════════════════════════════════════════════════════
CONSENT_ARTEFACT = {
    "consentId": "CONSENT-ABDM-9901-TATA-APOLLO",
    "status": "GRANTED",
    "patient": {
        "id": "PAT-ABDM-001",
        "abhaId": "14-8765-4321-9876",
        "name": "Anita Deshmukh"
    },
    "purpose": {
        "code": "CARETREAT",
        "text": "Care and Treatment"
    },
    "hiTypes": [
        "DischargeSummary",
        "DiagnosticReport",
        "OPConsultation"
    ],
    "permission": {
        "accessMode": "VIEW",
        "dateRange": {
            "from": "2026-01-01T00:00:00Z",
            "to": "2026-12-31T23:59:59Z"
        },
        "dataEraseAt": "2027-01-01T00:00:00Z",
        "frequency": {
            "unit": "HOUR",
            "value": 1,
            "repeats": 0
        }
    },
    "consentManager": {
        "id": "sbx-cm-01",
        "name": "ABDM Ayushman Bharat Digital Mission"
    },
    "hospitalsIncluded": [
        {"tenantId": "TMH-MUMBAI", "name": "Tata Memorial Hospital, Mumbai"},
        {"tenantId": "APOLLO-BLR", "name": "Apollo Hospitals, Bengaluru"},
        {"tenantId": "AIIMS-DEL", "name": "AIIMS, New Delhi"}
    ]
}

def save_files():
    """Saves all generated notes to files."""
    # Batch files
    p1_batch_file = os.path.join(OUTPUT_DIR, "pat_abdm_001_batch.json")
    with open(p1_batch_file, "w") as f:
        json.dump(P1_RAW_NOTES, f, indent=2)
    print(f"Saved: {p1_batch_file} ({len(P1_RAW_NOTES)} notes)")

    p2_batch_file = os.path.join(OUTPUT_DIR, "pat_abdm_002_batch.json")
    with open(p2_batch_file, "w") as f:
        json.dump(P2_RAW_NOTES, f, indent=2)
    print(f"Saved: {p2_batch_file} ({len(P2_RAW_NOTES)} notes)")

    consent_file = os.path.join(OUTPUT_DIR, "consent_artefact_abdm_9901.json")
    with open(consent_file, "w") as f:
        json.dump(CONSENT_ARTEFACT, f, indent=2)
    print(f"Saved: {consent_file}")

    # Individual notes
    for note in P1_RAW_NOTES + P2_RAW_NOTES:
        fpath = os.path.join(OUTPUT_DIR, f"{note['noteId'].lower()}.json")
        with open(fpath, "w") as f:
            json.dump(note, f, indent=2)

def index_into_opensearch(os_endpoint="http://13.202.49.76:9200", index_name="clinical-notes-v1"):
    """Directly indexes masked notes into OpenSearch using bulk API."""
    all_masked = P1_MASKED_NOTES + P2_MASKED_NOTES
    ndjson_lines = []
    for note in all_masked:
        action = {"index": {"_index": index_name, "_id": note["noteId"]}}
        ndjson_lines.append(json.dumps(action))
        ndjson_lines.append(json.dumps(note))
    ndjson_body = "\n".join(ndjson_lines) + "\n"

    bulk_url = f"{os_endpoint.rstrip('/')}/_bulk?refresh=true"
    print(f"Bulk indexing {len(all_masked)} notes into OpenSearch: {bulk_url}")
    req = urllib.request.Request(
        bulk_url,
        data=ndjson_body.encode("utf-8"),
        headers={"Content-Type": "application/x-ndjson"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            errors = body.get("errors", False)
            print(f"OpenSearch bulk index status: {resp.status} (has_errors: {errors})")
            if errors:
                for item in body.get("items", []):
                    idx_res = item.get("index", {})
                    if "error" in idx_res:
                        print(f"  Error on {idx_res.get('_id')}: {idx_res.get('error')}")
            else:
                print(f"Successfully indexed {len(all_masked)} multi-tenant notes into OpenSearch!")
    except Exception as e:
        print(f"Failed to index into OpenSearch: {e}")

def ingest_via_api(api_endpoint="https://l64zo3f94g.execute-api.ap-south-1.amazonaws.com/api/v1/notes"):
    """Ingests raw notes through official POST /api/v1/notes endpoint (stores in S3 & indexes to OpenSearch)."""
    all_raw = P1_RAW_NOTES + P2_RAW_NOTES
    print(f"Ingesting {len(all_raw)} notes via Ingestion API: {api_endpoint}")
    for note in all_raw:
        note_id = note["noteId"]
        tenant_id = note["tenantId"]
        payload = json.dumps(note).encode("utf-8")
        req = urllib.request.Request(
            api_endpoint,
            data=payload,
            headers={
                "Content-Type": "application/json",
                "X-Tenant-Id": tenant_id
            },
            method="POST"
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                res_body = json.loads(resp.read().decode("utf-8"))
                print(f"  ✓ Ingested {note_id} (Tenant: {tenant_id}) -> {res_body.get('storageUri', 'OK')}")
        except urllib.error.HTTPError as e:
            print(f"  ✗ HTTP Error for {note_id}: {e.code} - {e.read().decode('utf-8')}")
        except Exception as e:
            print(f"  ✗ Ingestion failed for {note_id}: {e}")

if __name__ == "__main__":
    save_files()
    # Ingest through official API endpoint (populates S3 and OpenSearch)
    ingest_via_api()
    # Also verify/direct index into OpenSearch on EC2
    index_into_opensearch()
