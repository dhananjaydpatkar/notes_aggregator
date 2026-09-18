package org.ncg.notes.common.adapter;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.port.DocumentStorePort;
import org.ncg.notes.common.util.JsonUtil;
import org.ncg.notes.common.util.S3PathBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3 Implementation of DocumentStorePort.
 * Stores notes deterministically under v1/tenants/{tenantId}/patients/{patientId}/{year}/{month}/{noteId}.json
 */
public class S3DocumentStore implements DocumentStorePort {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStore.class);

    private final S3Client s3Client;
    private final String bucketName;

    public S3DocumentStore(S3Client s3Client, String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName != null ? bucketName : "clinical-notes-store";
        ensureBucketExists();
    }

    private void ensureBucketExists() {
        try {
            s3Client.createBucket(software.amazon.awssdk.services.s3.model.CreateBucketRequest.builder()
                .bucket(bucketName)
                .build());
            log.info("Ensured S3 bucket exists: {}", bucketName);
        } catch (Exception e) {
            log.debug("S3 bucket check: {}", e.getMessage());
        }
    }

    @Override
    public String storeDocument(MaskedNote note) {
        String key = S3PathBuilder.buildObjectKey(note);
        byte[] payload = JsonUtil.toJsonBytes(note);

        try {
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/json")
                    .build(),
                RequestBody.fromBytes(payload)
            );
            String storageUri = String.format("s3://%s/%s", bucketName, key);
            log.info("Successfully stored note in S3: {}", storageUri);
            return storageUri;
        } catch (Exception e) {
            log.error("Failed to store note in S3 bucket={} key={}: {}", bucketName, key, e.getMessage(), e);
            throw new RuntimeException("S3 store operation failed for key: " + key + " - Cause: " + e.getClass().getSimpleName() + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public List<String> listDocumentKeysByPatient(String tenantId, String patientId) {
        String prefix = S3PathBuilder.buildPatientPrefix(tenantId, patientId);
        List<String> keys = new ArrayList<>();

        try {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();

            ListObjectsV2Response response = s3Client.listObjectsV2(request);
            for (S3Object s3Object : response.contents()) {
                if (s3Object.key().endsWith(".json")) {
                    keys.add(s3Object.key());
                }
            }
        } catch (Exception e) {
            log.error("Failed to list objects in S3 for prefix {}: {}", prefix, e.getMessage());
        }

        return keys;
    }

    @Override
    public List<MaskedNote> fetchAllByPatient(String tenantId, String patientId) {
        List<String> keys = listDocumentKeysByPatient(tenantId, patientId);
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }

        List<MaskedNote> notes = new ArrayList<>();
        for (String key : keys) {
            try {
                InputStream is = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
                byte[] bytes = is.readAllBytes();
                MaskedNote note = JsonUtil.fromJson(bytes, MaskedNote.class);
                if (note != null) {
                    notes.add(note);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch/parse S3 object {}: {}", key, e.getMessage());
            }
        }

        log.info("Fetched {} notes from S3 for patientId={}", notes.size(), patientId);
        return notes;
    }

    @Override
    public Optional<MaskedNote> fetchDocument(String tenantId, String patientId, String noteId) {
        List<String> keys = listDocumentKeysByPatient(tenantId, patientId);
        for (String key : keys) {
            if (key.contains(noteId)) {
                try {
                    InputStream is = s3Client.getObject(GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build());
                    byte[] bytes = is.readAllBytes();
                    return Optional.ofNullable(JsonUtil.fromJson(bytes, MaskedNote.class));
                } catch (NoSuchKeyException e) {
                    return Optional.empty();
                } catch (Exception e) {
                    log.error("Failed to read S3 note {}: {}", key, e.getMessage());
                }
            }
        }
        return Optional.empty();
    }
}
