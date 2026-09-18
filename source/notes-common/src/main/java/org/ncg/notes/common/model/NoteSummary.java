package org.ncg.notes.common.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record NoteSummary(
    String noteId,
    String noteType,
    Author author,
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    Instant authoredAt,
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    Instant recordedAt,
    String sourceSystem,
    String facilityId,
    String summary,
    String storageUri,
    String tenantId
) {
    public NoteSummary(
        String noteId,
        String noteType,
        Author author,
        Instant authoredAt,
        Instant recordedAt,
        String sourceSystem,
        String facilityId,
        String summary,
        String storageUri
    ) {
        this(noteId, noteType, author, authoredAt, recordedAt, sourceSystem, facilityId, summary, storageUri, null);
    }

    public static NoteSummary fromMaskedNote(MaskedNote note, String storageUri) {
        return new NoteSummary(
            note.noteId(),
            note.noteType(),
            note.author(),
            note.timestamps() != null ? note.timestamps().authoredAt() : null,
            note.timestamps() != null ? note.timestamps().recordedAt() : null,
            note.sourceSystem(),
            note.facilityId(),
            note.summary(),
            storageUri,
            note.tenantId()
        );
    }
}
