package org.ncg.notes.common.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.ncg.notes.common.model.MaskedNote;

public final class S3PathBuilder {

    private S3PathBuilder() {}

    /**
     * Builds S3 object key formatted as:
     * v1/tenants/{tenantId}/patients/{patientId}/{year}/{month}/{noteId}.json
     */
    public static String buildObjectKey(MaskedNote note) {
        String tenantId = sanitize(note.tenantId() != null ? note.tenantId() : "DEFAULT_TENANT");
        String patientId = sanitize(note.patientId());
        String noteId = sanitize(note.noteId());

        Instant authoredAt = (note.timestamps() != null && note.timestamps().authoredAt() != null)
            ? note.timestamps().authoredAt()
            : Instant.now();

        ZonedDateTime zdt = authoredAt.atZone(ZoneOffset.UTC);
        int year = zdt.getYear();
        String month = String.format("%02d", zdt.getMonthValue());

        return String.format("v1/tenants/%s/patients/%s/%d/%s/%s.json",
            tenantId, patientId, year, month, noteId);
    }

    /**
     * Builds S3 prefix for a patient:
     * v1/tenants/{tenantId}/patients/{patientId}/
     */
    public static String buildPatientPrefix(String tenantId, String patientId) {
        return String.format("v1/tenants/%s/patients/%s/",
            sanitize(tenantId != null ? tenantId : "DEFAULT_TENANT"),
            sanitize(patientId));
    }

    private static String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return "UNKNOWN";
        }
        return input.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
