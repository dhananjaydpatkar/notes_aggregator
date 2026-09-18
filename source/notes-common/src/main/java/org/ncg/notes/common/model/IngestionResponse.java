package org.ncg.notes.common.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngestionResponse(
    String status,
    String noteId,
    String tenantId,
    String patientId,
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    Instant indexedAt,
    String storageUri
) {
    public static IngestionResponse success(String noteId, String tenantId, String patientId, String storageUri) {
        return new IngestionResponse("SUCCESS", noteId, tenantId, patientId, Instant.now(), storageUri);
    }
}
