package org.ncg.notes.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PatientNotesResponse(
    String patientId,
    String tenantId,
    long totalNotes,
    int page,
    int limit,
    String servedFrom,
    List<NoteSummary> notes
) {
    public static PatientNotesResponse of(
        String patientId,
        String tenantId,
        long totalNotes,
        int page,
        int limit,
        String servedFrom,
        List<NoteSummary> notes
    ) {
        return new PatientNotesResponse(
            patientId,
            tenantId,
            totalNotes,
            page,
            limit,
            servedFrom,
            notes != null ? notes : Collections.emptyList()
        );
    }
}
