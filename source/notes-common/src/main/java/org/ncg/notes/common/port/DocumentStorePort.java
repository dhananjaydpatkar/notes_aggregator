package org.ncg.notes.common.port;

import java.util.List;
import java.util.Optional;
import org.ncg.notes.common.model.MaskedNote;

/**
 * Port interface for durable immutable document storage.
 * Pluggable implementation: AWS S3, LocalStack S3, or Local Filesystem.
 */
public interface DocumentStorePort {
    /**
     * Store note payload in durable storage.
     * @return Storage URI (e.g., s3://bucket/v1/tenants/tid/patients/pid/...)
     */
    String storeDocument(MaskedNote note);

    /**
     * List all document storage keys for a patient under tenant prefix.
     */
    List<String> listDocumentKeysByPatient(String tenantId, String patientId);

    /**
     * Fetch all notes for a patient from durable storage (used for lazy rehydration).
     */
    List<MaskedNote> fetchAllByPatient(String tenantId, String patientId);

    /**
     * Fetch a single document by its key or noteId.
     */
    Optional<MaskedNote> fetchDocument(String tenantId, String patientId, String noteId);
}
