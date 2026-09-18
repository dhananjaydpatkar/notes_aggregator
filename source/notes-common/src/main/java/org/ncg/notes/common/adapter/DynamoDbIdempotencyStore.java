package org.ncg.notes.common.adapter;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.ncg.notes.common.port.IdempotencyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * DynamoDB implementation of IdempotencyPort.
 * Stores idempotency keys with TTL attribute expiresAt for auto-expiry.
 */
public class DynamoDbIdempotencyStore implements IdempotencyPort {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbIdempotencyStore.class);

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbIdempotencyStore(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName != null ? tableName : "notes-idempotency";
    }

    @Override
    public Optional<String> findExistingNoteId(String tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        String pk = formatPk(tenantId, idempotencyKey);
        try {
            GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("pk", AttributeValue.builder().s(pk).build()))
                .consistentRead(true)
                .build());

            if (response.hasItem() && response.item().containsKey("noteId")) {
                String existingNoteId = response.item().get("noteId").s();
                log.info("Idempotency match found for pk={}: noteId={}", pk, existingNoteId);
                return Optional.of(existingNoteId);
            }
        } catch (Exception e) {
            log.warn("Failed to check idempotency in DynamoDB for pk={}: {}", pk, e.getMessage());
        }

        return Optional.empty();
    }

    @Override
    public void saveIdempotencyRecord(String tenantId, String idempotencyKey, String noteId, long ttlSeconds) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || noteId == null) {
            return;
        }

        String pk = formatPk(tenantId, idempotencyKey);
        long expiresAt = Instant.now().getEpochSecond() + (ttlSeconds > 0 ? ttlSeconds : 86400);

        try {
            dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                    "pk", AttributeValue.builder().s(pk).build(),
                    "noteId", AttributeValue.builder().s(noteId).build(),
                    "tenantId", AttributeValue.builder().s(tenantId != null ? tenantId : "DEFAULT_TENANT").build(),
                    "createdAt", AttributeValue.builder().s(Instant.now().toString()).build(),
                    "expiresAt", AttributeValue.builder().n(String.valueOf(expiresAt)).build()
                ))
                .build());
            log.debug("Saved idempotency record for pk={} noteId={}", pk, noteId);
        } catch (Exception e) {
            log.error("Failed to save idempotency record for pk={}: {}", pk, e.getMessage());
        }
    }

    private String formatPk(String tenantId, String idempotencyKey) {
        String safeTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : "DEFAULT_TENANT";
        return String.format("%s#%s", safeTenant, idempotencyKey.trim());
    }
}
