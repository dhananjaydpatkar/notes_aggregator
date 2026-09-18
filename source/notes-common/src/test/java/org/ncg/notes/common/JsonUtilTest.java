package org.ncg.notes.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.ncg.notes.common.model.Author;
import org.ncg.notes.common.model.ClinicalNote;
import org.ncg.notes.common.model.Demographics;
import org.ncg.notes.common.model.NoteContent;
import org.ncg.notes.common.model.Timestamps;
import org.ncg.notes.common.util.JsonUtil;
import org.ncg.notes.common.util.S3PathBuilder;

class JsonUtilTest {

    @Test
    void shouldSerializeAndDeserializeClinicalNote() {
        ClinicalNote note = ClinicalNote.builder()
            .noteId("NOTE-12345")
            .tenantId("HOSP-WEST")
            .patientId("PAT-999")
            .sourceSystem("MOIS")
            .noteType("MEDICAL_ONCOLOGY_PROGRESS")
            .author(Author.builder().clinicianId("DOC-1").name("Dr. Smith").department("Med Onc").build())
            .demographics(Demographics.builder().age(58).gender("M").abhaId("12-3456-7890-1234").build())
            .timestamps(Timestamps.builder().authoredAt(Instant.parse("2026-08-18T10:30:00Z")).build())
            .content(NoteContent.builder()
                .title("Progress Note")
                .sections(Map.of("assessment", "Stage III colon cancer"))
                .rawText("Progress Note: Stage III colon cancer")
                .build())
            .build();

        String json = JsonUtil.toJson(note);
        assertThat(json).contains("NOTE-12345", "HOSP-WEST", "PAT-999", "12-3456-7890-1234");

        ClinicalNote deserialized = JsonUtil.fromJson(json, ClinicalNote.class);
        assertThat(deserialized.noteId()).isEqualTo("NOTE-12345");
        assertThat(deserialized.demographics().abhaId()).isEqualTo("12-3456-7890-1234");
        assertThat(deserialized.author().name()).isEqualTo("Dr. Smith");
    }

    @Test
    void shouldBuildDeterministicS3Key() {
        ClinicalNote note = ClinicalNote.builder()
            .noteId("NOTE-12345")
            .tenantId("HOSP-WEST")
            .patientId("PAT-999")
            .timestamps(Timestamps.builder().authoredAt(Instant.parse("2026-08-18T10:30:00Z")).build())
            .build();

        String key = S3PathBuilder.buildObjectKey(
            org.ncg.notes.common.model.MaskedNote.builder()
                .noteId(note.noteId())
                .tenantId(note.tenantId())
                .patientId(note.patientId())
                .timestamps(note.timestamps())
                .build()
        );

        assertThat(key).isEqualTo("v1/tenants/HOSP-WEST/patients/PAT-999/2026/08/NOTE-12345.json");
    }
}
