package org.ncg.notes.common.port;

import java.util.Optional;

/**
 * Port interface for idempotency check and deduplication.
 * Pluggable implementation: DynamoDB conditional put, In-Memory map, or ElastiCache Redis.
 */
public interface IdempotencyPort {
    /**
     * Checks if the given idempotency key was already processed for this tenant.
     * @return Optional containing previous noteId if duplicate, or empty if new.
     */
    Optional<String> findExistingNoteId(String tenantId, String idempotencyKey);

    /**
     * Records a processed idempotency key mapped to the generated noteId.
     */
    void saveIdempotencyRecord(String tenantId, String idempotencyKey, String noteId, long ttlSeconds);
}
