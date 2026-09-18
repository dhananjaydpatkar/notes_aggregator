package org.ncg.notes.common.port;

import java.util.List;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.model.PatientNotesResponse;

/**
 * Port interface for indexing and querying clinical notes.
 * Pluggable implementation: OpenSearch Single-Node REST, Amazon OpenSearch Serverless (AOSS), or In-Memory index.
 */
public interface SearchIndexPort {
    /**
     * Synchronously index a single masked clinical note.
     */
    void indexNote(MaskedNote note);

    /**
     * Bulk index multiple masked notes (used for batch and lazy rehydration).
     */
    void bulkIndex(List<MaskedNote> notes);

    /**
     * Count notes for a patient in the warm index.
     */
    long countByPatient(String tenantId, String patientId);

    /**
     * Retrieve notes for a patient sorted authoredAt DESC.
     */
    PatientNotesResponse findByPatient(String tenantId, String patientId, int page, int limit, String servedFrom);

    /**
     * Evict all notes for a specific patient from the warm index.
     * @return Number of documents deleted from index.
     */
    long evictPatient(String tenantId, String patientId);

    /**
     * Retrieve a single masked note by its noteId from the warm index.
     * Returns null if not found.
     */
    MaskedNote findNoteById(String noteId);
}
