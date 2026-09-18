package org.ncg.notes.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexEvictionResponse(
    String patientId,
    String tenantId,
    long deletedFromIndex,
    boolean dataRetainedInS3,
    String message
) {
    public static IndexEvictionResponse success(String patientId, String tenantId, long deletedFromIndex) {
        return new IndexEvictionResponse(
            patientId,
            tenantId,
            deletedFromIndex,
            true,
            "Patient data evicted from warm index. Data intact in S3. Next read will rehydrate automatically."
        );
    }
}
