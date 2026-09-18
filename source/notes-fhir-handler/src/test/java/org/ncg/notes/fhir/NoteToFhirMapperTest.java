package org.ncg.notes.fhir;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ncg.notes.common.model.Author;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.model.NoteContent;
import org.ncg.notes.common.model.NoteSummary;
import org.ncg.notes.common.model.Timestamps;
import org.ncg.notes.fhir.mapper.NoteToFhirMapper;
import org.ncg.notes.fhir.model.FhirBundle;
import org.ncg.notes.fhir.model.FhirDocumentReference;

import static org.assertj.core.api.Assertions.assertThat;

class NoteToFhirMapperTest {

    private NoteToFhirMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new NoteToFhirMapper();
    }

    @Test
    void toDocumentReference_shouldMapMaskedNoteToFhirDocumentReference() {
        MaskedNote note = MaskedNote.builder()
            .noteId("NOTE-12345")
            .tenantId("HOSP-WEST")
            .patientId("PAT-9082341")
            .sourceSystem("MOIS")
            .facilityId("HOSP-WEST-01")
            .encounterId("ENC-99")
            .noteType("MEDICAL_ONCOLOGY_PROGRESS")
            .author(new Author("DOC-101", "[PHI:CLINICIAN:abc]", "Medical Oncology"))
            .timestamps(Timestamps.builder()
                .authoredAt(Instant.parse("2026-08-18T10:30:00Z"))
                .recordedAt(Instant.parse("2026-08-18T10:32:00Z"))
                .build())
            .summary("Cycle 3 FOLFOX-6 evaluation")
            .content(NoteContent.builder()
                .title("Progress Note")
                .rawText("Patient tolerating chemo.")
                .build())
            .build();

        FhirDocumentReference doc = mapper.toDocumentReference(note);

        assertThat(doc).isNotNull();
        assertThat(doc.resourceType()).isEqualTo("DocumentReference");
        assertThat(doc.id()).isEqualTo("NOTE-12345");
        assertThat(doc.status()).isEqualTo("current");
        assertThat(doc.subject().reference()).isEqualTo("Patient/PAT-9082341");
        assertThat(doc.description()).isEqualTo("Cycle 3 FOLFOX-6 evaluation");

        // LOINC mapping verification
        assertThat(doc.type().coding()).hasSize(1);
        assertThat(doc.type().coding().get(0).system()).isEqualTo("http://loinc.org");
        assertThat(doc.type().coding().get(0).code()).isEqualTo("11506-3");
        assertThat(doc.type().coding().get(0).display()).isEqualTo("Progress note");

        // Author verification
        assertThat(doc.author()).hasSize(1);
        assertThat(doc.author().get(0).reference()).isEqualTo("Practitioner/DOC-101");
        assertThat(doc.author().get(0).display()).isEqualTo("[PHI:CLINICIAN:abc]");

        // Attachment verification
        assertThat(doc.content()).hasSize(1);
        assertThat(doc.content().get(0).attachment().url()).isEqualTo("/api/v1/notes/NOTE-12345/document");
    }

    @Test
    void toBundle_shouldWrapDocumentReferencesInSearchsetBundle() {
        NoteSummary summary = new NoteSummary(
            "NOTE-111",
            "PATHOLOGY_REPORT",
            new Author("DOC-202", "[PHI:CLINICIAN:xyz]", "Pathology"),
            Instant.parse("2026-06-14T11:20:00Z"),
            Instant.parse("2026-06-14T11:25:00Z"),
            "LIMS",
            "LAB-01",
            "Sigmoid adenocarcinoma pT3N1b",
            "/api/v1/notes/NOTE-111/document"
        );

        FhirDocumentReference doc = mapper.toDocumentReference(summary, "HOSP-WEST");
        FhirBundle bundle = mapper.toBundle(List.of(doc), 1, "/fhir/r4/DocumentReference?patient=PAT-1");

        assertThat(bundle.resourceType()).isEqualTo("Bundle");
        assertThat(bundle.type()).isEqualTo("searchset");
        assertThat(bundle.total()).isEqualTo(1);
        assertThat(bundle.entry()).hasSize(1);
        assertThat(bundle.entry().get(0).fullUrl()).isEqualTo("/fhir/r4/DocumentReference/NOTE-111");
        assertThat(bundle.entry().get(0).resource().type().coding().get(0).code()).isEqualTo("11529-5");
    }

    @Test
    void loincToNoteType_shouldReverseMapLoincCodes() {
        assertThat(mapper.loincToNoteType("11506-3")).isEqualTo("MEDICAL_ONCOLOGY_PROGRESS");
        assertThat(mapper.loincToNoteType("11504-8")).isEqualTo("OPERATIVE_NOTE");
        assertThat(mapper.loincToNoteType("11529-5")).isEqualTo("PATHOLOGY_REPORT");
        assertThat(mapper.loincToNoteType("18842-5")).isEqualTo("DISCHARGE_SUMMARY");
        assertThat(mapper.loincToNoteType("unknown")).isNull();
    }
}
