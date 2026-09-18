package org.ncg.notes.fhir.mapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.ncg.notes.common.model.Author;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.model.NoteSummary;
import org.ncg.notes.common.model.Timestamps;
import org.ncg.notes.fhir.model.FhirAttachment;
import org.ncg.notes.fhir.model.FhirBundle;
import org.ncg.notes.fhir.model.FhirCodeableConcept;
import org.ncg.notes.fhir.model.FhirCoding;
import org.ncg.notes.fhir.model.FhirContent;
import org.ncg.notes.fhir.model.FhirDocumentReference;
import org.ncg.notes.fhir.model.FhirDocumentReference.FhirContext;
import org.ncg.notes.fhir.model.FhirExtension;
import org.ncg.notes.fhir.model.FhirIdentifier;
import org.ncg.notes.fhir.model.FhirReference;

/**
 * Maps between internal MaskedNote/NoteSummary and FHIR R4 DocumentReference resources.
 * <p>
 * LOINC codes sourced from: https://loinc.org/
 * ABDM identifiers: https://abdm.gov.in/
 */
public class NoteToFhirMapper {

    private static final String LOINC_SYSTEM = "http://loinc.org";
    private static final String ABDM_ABHA_SYSTEM = "https://abdm.gov.in/abha";
    private static final String CLINICAL_EXT_BASE = "https://clinicalnotes.org/fhir/StructureDefinition/";

    /** Internal noteType → LOINC code+display */
    private static final Map<String, FhirCoding> LOINC_MAP = Map.of(
        "MEDICAL_ONCOLOGY_PROGRESS", new FhirCoding(LOINC_SYSTEM, "11506-3", "Progress note"),
        "TREATMENT_PLAN",            new FhirCoding(LOINC_SYSTEM, "18776-5", "Plan of care note"),
        "PATHOLOGY_REPORT",          new FhirCoding(LOINC_SYSTEM, "11529-5", "Surgical pathology study"),
        "RADIOLOGY_REPORT",          new FhirCoding(LOINC_SYSTEM, "18748-4", "Diagnostic imaging study"),
        "DISCHARGE_SUMMARY",         new FhirCoding(LOINC_SYSTEM, "18842-5", "Discharge summary"),
        "OPERATIVE_NOTE",            new FhirCoding(LOINC_SYSTEM, "11504-8", "Surgical operation note"),
        "LAB_REPORT",                new FhirCoding(LOINC_SYSTEM, "11502-2", "Laboratory report")
    );

    /** Reverse: LOINC code → noteType */
    private static final Map<String, String> REVERSE_LOINC = Map.of(
        "11506-3", "MEDICAL_ONCOLOGY_PROGRESS",
        "18776-5", "TREATMENT_PLAN",
        "11529-5", "PATHOLOGY_REPORT",
        "18748-4", "RADIOLOGY_REPORT",
        "18842-5", "DISCHARGE_SUMMARY",
        "11504-8", "OPERATIVE_NOTE",
        "11502-2", "LAB_REPORT"
    );

    /**
     * Converts a fully hydrated MaskedNote → FhirDocumentReference.
     */
    public FhirDocumentReference toDocumentReference(MaskedNote note) {
        String storageUri = "/api/v1/notes/" + note.noteId() + "/document";

        FhirCoding loinc = LOINC_MAP.getOrDefault(note.noteType(),
            new FhirCoding(LOINC_SYSTEM, "34109-9", "Note")); // fallback: generic note

        // Subject (patient reference)
        FhirReference subject = new FhirReference(
            "Patient/" + note.patientId(),
            note.patientId(),
            null
        );

        // Author (masked clinician)
        List<FhirReference> authors = Collections.emptyList();
        if (note.author() != null) {
            authors = List.of(new FhirReference(
                "Practitioner/" + note.author().clinicianId(),
                note.author().name(),   // already masked e.g. [PHI:CLINICIAN:xxx]
                null
            ));
        }

        // Date
        String date = null;
        if (note.timestamps() != null && note.timestamps().authoredAt() != null) {
            date = note.timestamps().authoredAt().toString();
        }

        // Content attachment
        FhirContent content = new FhirContent(new FhirAttachment(
            "application/json",
            storageUri,
            note.noteType() != null ? note.noteType().replace("_", " ") : "Clinical Note",
            date
        ));

        // Context (encounter, facility)
        FhirContext ctx = null;
        if (note.encounterId() != null || note.facilityId() != null) {
            FhirReference encounter = note.encounterId() != null
                ? new FhirReference("Encounter/" + note.encounterId(), note.encounterId(), null)
                : null;
            FhirReference facility = note.facilityId() != null
                ? new FhirReference(null, note.facilityId(), null)
                : null;
            ctx = new FhirContext(
                encounter != null ? List.of(encounter) : null,
                facility
            );
        }

        // Clinical extensions
        List<FhirExtension> extensions = new ArrayList<>();
        if (note.tenantId() != null) {
            extensions.add(new FhirExtension(CLINICAL_EXT_BASE + "tenant-id", note.tenantId()));
        }
        if (note.sourceSystem() != null) {
            extensions.add(new FhirExtension(CLINICAL_EXT_BASE + "source-system", note.sourceSystem()));
        }

        return new FhirDocumentReference(
            "DocumentReference",
            note.noteId(),
            List.of(new FhirIdentifier("https://clinicalnotes.org/fhir/note-id", note.noteId())),
            "current",
            new FhirCodeableConcept(List.of(loinc), note.noteType()),
            null,
            subject,
            date,
            authors,
            note.summary(),
            List.of(content),
            ctx,
            extensions.isEmpty() ? null : extensions
        );
    }

    /**
     * Converts a lightweight NoteSummary → FhirDocumentReference (for search results).
     * Avoids S3 fetch — uses fields available in the OpenSearch index.
     */
    public FhirDocumentReference toDocumentReference(NoteSummary summary, String tenantId) {
        FhirCoding loinc = LOINC_MAP.getOrDefault(summary.noteType(),
            new FhirCoding(LOINC_SYSTEM, "34109-9", "Note"));

        String patientRef = summary.storageUri() != null
            ? summary.storageUri().replaceAll("/api/v1/notes/[^/]+/document", "")
            : "Patient/unknown";

        String date = summary.authoredAt() != null ? summary.authoredAt().toString() : null;

        List<FhirReference> authors = Collections.emptyList();
        if (summary.author() != null) {
            authors = List.of(new FhirReference(
                "Practitioner/" + summary.author().clinicianId(),
                summary.author().name(),
                null
            ));
        }

        FhirContent content = new FhirContent(new FhirAttachment(
            "application/json",
            summary.storageUri(),
            summary.noteType() != null ? summary.noteType().replace("_", " ") : "Note",
            date
        ));

        List<FhirExtension> extensions = new ArrayList<>();
        if (tenantId != null) {
            extensions.add(new FhirExtension(CLINICAL_EXT_BASE + "tenant-id", tenantId));
        }
        if (summary.sourceSystem() != null) {
            extensions.add(new FhirExtension(CLINICAL_EXT_BASE + "source-system", summary.sourceSystem()));
        }

        return new FhirDocumentReference(
            "DocumentReference",
            summary.noteId(),
            List.of(new FhirIdentifier("https://clinicalnotes.org/fhir/note-id", summary.noteId())),
            "current",
            new FhirCodeableConcept(List.of(loinc), summary.noteType()),
            null,
            new FhirReference(patientRef, null, null),
            date,
            authors,
            summary.summary(),
            List.of(content),
            null,
            extensions.isEmpty() ? null : extensions
        );
    }

    /**
     * Wraps a list of DocumentReferences in a FHIR Bundle (searchset).
     */
    public FhirBundle toBundle(List<FhirDocumentReference> docs, int total, String selfLink) {
        List<FhirBundle.FhirEntry> entries = docs.stream()
            .map(doc -> new FhirBundle.FhirEntry("/fhir/r4/DocumentReference/" + doc.id(), doc))
            .toList();

        List<FhirBundle.FhirLink> links = selfLink != null
            ? List.of(new FhirBundle.FhirLink("self", selfLink))
            : null;

        return new FhirBundle("Bundle", null, "searchset", total, links, entries);
    }

    /**
     * Resolve LOINC code back to internal noteType string.
     * Returns null if unknown.
     */
    public String loincToNoteType(String loincCode) {
        if (loincCode == null) return null;
        return REVERSE_LOINC.get(loincCode.trim());
    }
}
