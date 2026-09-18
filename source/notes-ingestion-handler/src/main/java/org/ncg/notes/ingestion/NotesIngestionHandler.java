package org.ncg.notes.ingestion;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.ncg.notes.common.adapter.DynamoDbIdempotencyStore;
import org.ncg.notes.common.adapter.OpenSearchIndexClient;
import org.ncg.notes.common.adapter.S3DocumentStore;
import org.ncg.notes.common.model.ClinicalNote;
import org.ncg.notes.common.model.IngestionResponse;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.port.DocumentStorePort;
import org.ncg.notes.common.port.IdempotencyPort;
import org.ncg.notes.common.port.PhiMaskingPort;
import org.ncg.notes.common.port.SearchIndexPort;
import org.ncg.notes.common.util.JsonUtil;
import org.ncg.notes.phi.IndianAndHipaaPhiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS Lambda handler for POST /api/v1/notes and POST /api/v1/notes/batch
 * Reference implementation: open endpoint (no authentication/authorization required).
 */
public class NotesIngestionHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LoggerFactory.getLogger(NotesIngestionHandler.class);

    private final PhiMaskingPort phiMaskingPort;
    private final IdempotencyPort idempotencyPort;
    private final SearchIndexPort searchIndexPort;
    private final DocumentStorePort documentStorePort;

    public NotesIngestionHandler() {
        this.phiMaskingPort = new IndianAndHipaaPhiMasker();

        String regionStr = System.getenv().getOrDefault("AWS_REGION_NAME", "ap-south-1");
        Region region = Region.of(regionStr);

        // DynamoDB Client
        String dynEndpoint = System.getenv("DYNAMODB_ENDPOINT");
        DynamoDbClient dynClient;
        if (dynEndpoint != null && !dynEndpoint.isBlank()) {
            dynClient = DynamoDbClient.builder()
                .endpointOverride(URI.create(dynEndpoint))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("mock", "mock")
                ))
                .region(region)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        } else {
            dynClient = DynamoDbClient.builder()
                .region(region)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        }
        String dynTable = System.getenv().getOrDefault("DYNAMODB_TABLE_NAME", "notes-idempotency");
        this.idempotencyPort = new DynamoDbIdempotencyStore(dynClient, dynTable);

        // S3 Client
        String s3Endpoint = System.getenv("S3_ENDPOINT");
        S3Client s3Client;
        if (s3Endpoint != null && !s3Endpoint.isBlank()) {
            s3Client = S3Client.builder()
                .endpointOverride(URI.create(s3Endpoint))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("mock", "mock")
                ))
                .region(region)
                .forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        } else {
            s3Client = S3Client.builder()
                .region(region)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        }
        String s3Bucket = System.getenv().getOrDefault("S3_BUCKET_NAME", "clinical-notes-store");
        this.documentStorePort = new S3DocumentStore(s3Client, s3Bucket);

        // OpenSearch Client
        String osEndpoint = System.getenv().getOrDefault("OPENSEARCH_ENDPOINT", "http://localhost:9200");
        String osIndex = System.getenv().getOrDefault("OPENSEARCH_INDEX", "clinical-notes-v1");
        this.searchIndexPort = new OpenSearchIndexClient(osEndpoint, osIndex);
    }

    public NotesIngestionHandler(
        PhiMaskingPort phiMaskingPort,
        IdempotencyPort idempotencyPort,
        SearchIndexPort searchIndexPort,
        DocumentStorePort documentStorePort
    ) {
        this.phiMaskingPort = phiMaskingPort;
        this.idempotencyPort = idempotencyPort;
        this.searchIndexPort = searchIndexPort;
        this.documentStorePort = documentStorePort;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        log.info("Received Ingestion Request: routeKey={}", event != null ? event.getRouteKey() : "null");

        if (event == null || event.getBody() == null || event.getBody().isBlank()) {
            return buildResponse(400, Map.of("error", "Request body is required"));
        }

        try {
            // Extract tenant from header or fallback
            String tenantHeader = getHeader(event, "x-tenant-id");
            String trimmedBody = event.getBody().trim();

            // ── BATCH INGESTION: Array of ClinicalNote ───────────────────────
            if (trimmedBody.startsWith("[")) {
                List<ClinicalNote> incomingNotes = JsonUtil.mapper().readValue(
                    trimmedBody,
                    new TypeReference<List<ClinicalNote>>() {}
                );

                if (incomingNotes.isEmpty()) {
                    return buildResponse(400, Map.of("error", "Batch notes list cannot be empty"));
                }

                List<MaskedNote> maskedNotes = new ArrayList<>();
                List<String> noteIds = new ArrayList<>();
                String batchPatientId = null;

                for (ClinicalNote incoming : incomingNotes) {
                    if (incoming.patientId() == null || incoming.patientId().isBlank()) {
                        return buildResponse(400, Map.of("error", "patientId is required for all notes in batch"));
                    }
                    if (incoming.noteType() == null || incoming.noteType().isBlank()) {
                        return buildResponse(400, Map.of("error", "noteType is required for all notes in batch"));
                    }
                    if (batchPatientId == null) {
                        batchPatientId = incoming.patientId();
                    }

                    String effectiveTenant = resolveTenant(tenantHeader, incoming.tenantId());
                    String noteId = (incoming.noteId() != null && !incoming.noteId().isBlank())
                        ? incoming.noteId()
                        : "NOTE-" + UUID.randomUUID();

                    ClinicalNote normalized = ClinicalNote.builder()
                        .noteId(noteId)
                        .tenantId(effectiveTenant)
                        .patientId(incoming.patientId())
                        .encounterId(incoming.encounterId())
                        .sourceSystem(incoming.sourceSystem() != null ? incoming.sourceSystem() : "GENERAL")
                        .facilityId(incoming.facilityId())
                        .noteType(incoming.noteType())
                        .author(incoming.author())
                        .demographics(incoming.demographics())
                        .timestamps(incoming.timestamps())
                        .content(incoming.content())
                        .coding(incoming.coding())
                        .build();

                    MaskedNote masked = phiMaskingPort.mask(normalized);
                    documentStorePort.storeDocument(masked);
                    maskedNotes.add(masked);
                    noteIds.add(noteId);
                }

                searchIndexPort.bulkIndex(maskedNotes);

                log.info("Processed batch ingestion: {} notes for patient {}", maskedNotes.size(), batchPatientId);
                return buildResponse(201, Map.of(
                    "status", "SUCCESS",
                    "mode", "BATCH",
                    "totalIngested", maskedNotes.size(),
                    "patientId", batchPatientId != null ? batchPatientId : "",
                    "noteIds", noteIds
                ));
            }

            // ── SINGLE NOTE INGESTION ─────────────────────────────────────────
            ClinicalNote incomingNote = JsonUtil.fromJson(event.getBody(), ClinicalNote.class);

            if (incomingNote.patientId() == null || incomingNote.patientId().isBlank()) {
                return buildResponse(400, Map.of("error", "patientId is required"));
            }
            if (incomingNote.noteType() == null || incomingNote.noteType().isBlank()) {
                return buildResponse(400, Map.of("error", "noteType is required"));
            }

            // Resolve effective tenant
            String effectiveTenant = resolveTenant(tenantHeader, incomingNote.tenantId());

            // Check Idempotency Key
            String idempotencyKey = getHeader(event, "idempotency-key");
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Optional<String> existingNoteId = idempotencyPort.findExistingNoteId(effectiveTenant, idempotencyKey);
                if (existingNoteId.isPresent()) {
                    log.info("Duplicate request detected for key {}: returning existing noteId {}", idempotencyKey, existingNoteId.get());
                    IngestionResponse dedupeResponse = IngestionResponse.success(
                        existingNoteId.get(),
                        effectiveTenant,
                        incomingNote.patientId(),
                        String.format("/api/v1/notes/%s/document", existingNoteId.get())
                    );
                    return buildResponse(200, dedupeResponse);
                }
            }

            // Assign noteId if not present
            String noteId = (incomingNote.noteId() != null && !incomingNote.noteId().isBlank())
                ? incomingNote.noteId()
                : "NOTE-" + UUID.randomUUID();

            // Populate normalized ClinicalNote
            ClinicalNote normalizedNote = ClinicalNote.builder()
                .noteId(noteId)
                .tenantId(effectiveTenant)
                .patientId(incomingNote.patientId())
                .encounterId(incomingNote.encounterId())
                .sourceSystem(incomingNote.sourceSystem() != null ? incomingNote.sourceSystem() : "GENERAL")
                .facilityId(incomingNote.facilityId())
                .noteType(incomingNote.noteType())
                .author(incomingNote.author())
                .demographics(incomingNote.demographics())
                .timestamps(incomingNote.timestamps())
                .content(incomingNote.content())
                .coding(incomingNote.coding())
                .build();

            // 1. PHI Masking
            MaskedNote maskedNote = phiMaskingPort.mask(normalizedNote);

            // 2. Index in OpenSearch
            searchIndexPort.indexNote(maskedNote);

            // 3. Persist in durable S3 storage
            String storageUri = documentStorePort.storeDocument(maskedNote);

            // 4. Save Idempotency mapping
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyPort.saveIdempotencyRecord(effectiveTenant, idempotencyKey, noteId, 86400);
            }

            // Return success response
            IngestionResponse responsePayload = IngestionResponse.success(
                noteId,
                effectiveTenant,
                normalizedNote.patientId(),
                storageUri
            );

            return buildResponse(201, responsePayload);

        } catch (IllegalArgumentException e) {
            log.warn("Validation error during note ingestion: {}", e.getMessage());
            return buildResponse(400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unhandled error during note ingestion: {}", e.getMessage(), e);
            return buildResponse(500, Map.of("error", "Internal Server Error: " + e.getMessage()));
        }
    }

    private String resolveTenant(String headerTenant, String bodyTenant) {
        if (headerTenant != null && !headerTenant.isBlank()) {
            return headerTenant.trim();
        }
        if (bodyTenant != null && !bodyTenant.isBlank()) {
            return bodyTenant.trim();
        }
        return "DEFAULT_TENANT";
    }

    private String getHeader(APIGatewayV2HTTPEvent event, String targetHeader) {
        if (event.getHeaders() == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : event.getHeaders().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(targetHeader)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private APIGatewayV2HTTPResponse buildResponse(int statusCode, Object body) {
        return APIGatewayV2HTTPResponse.builder()
            .withStatusCode(statusCode)
            .withHeaders(Map.of(
                "Content-Type", "application/json",
                "Access-Control-Allow-Origin", "*",
                "Access-Control-Allow-Headers", "Content-Type,X-Tenant-Id,Idempotency-Key,X-Consent-Artefact-Id",
                "Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS"
            ))
            .withBody(JsonUtil.toJson(body))
            .build();
    }
}
