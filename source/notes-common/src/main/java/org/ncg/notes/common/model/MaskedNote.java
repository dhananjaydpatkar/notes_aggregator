package org.ncg.notes.common.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaskedNote(
    String noteId,
    String tenantId,
    String patientId,
    String encounterId,
    String sourceSystem,
    String facilityId,
    String noteType,
    Author author,
    Demographics demographics,
    Timestamps timestamps,
    NoteContent content,
    List<CodingEntry> coding,
    String summary,
    MaskingMetadata maskingMetadata
) {
    public record MaskingMetadata(
        @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
        Instant maskedAt,
        int tokensMaskedCount,
        String maskingEngine
    ) {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String noteId;
        private String tenantId = "DEFAULT_TENANT";
        private String patientId;
        private String encounterId;
        private String sourceSystem;
        private String facilityId;
        private String noteType;
        private Author author;
        private Demographics demographics;
        private Timestamps timestamps;
        private NoteContent content;
        private List<CodingEntry> coding = Collections.emptyList();
        private String summary;
        private MaskingMetadata maskingMetadata;

        public Builder noteId(String noteId) {
            this.noteId = noteId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId != null ? tenantId : "DEFAULT_TENANT";
            return this;
        }

        public Builder patientId(String patientId) {
            this.patientId = patientId;
            return this;
        }

        public Builder encounterId(String encounterId) {
            this.encounterId = encounterId;
            return this;
        }

        public Builder sourceSystem(String sourceSystem) {
            this.sourceSystem = sourceSystem;
            return this;
        }

        public Builder facilityId(String facilityId) {
            this.facilityId = facilityId;
            return this;
        }

        public Builder noteType(String noteType) {
            this.noteType = noteType;
            return this;
        }

        public Builder author(Author author) {
            this.author = author;
            return this;
        }

        public Builder demographics(Demographics demographics) {
            this.demographics = demographics;
            return this;
        }

        public Builder timestamps(Timestamps timestamps) {
            this.timestamps = timestamps;
            return this;
        }

        public Builder content(NoteContent content) {
            this.content = content;
            return this;
        }

        public Builder coding(List<CodingEntry> coding) {
            this.coding = coding != null ? coding : Collections.emptyList();
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder maskingMetadata(MaskingMetadata maskingMetadata) {
            this.maskingMetadata = maskingMetadata;
            return this;
        }

        public MaskedNote build() {
            return new MaskedNote(
                noteId,
                tenantId != null ? tenantId : "DEFAULT_TENANT",
                patientId,
                encounterId,
                sourceSystem,
                facilityId,
                noteType,
                author,
                demographics,
                timestamps != null ? timestamps : Timestamps.builder().build(),
                content,
                coding,
                summary,
                maskingMetadata != null ? maskingMetadata : new MaskingMetadata(Instant.now(), 0, "NONE")
            );
        }
    }
}
