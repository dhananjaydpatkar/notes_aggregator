package org.ncg.notes.query;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.ncg.notes.common.adapter.OpenSearchIndexClient;
import org.ncg.notes.common.adapter.S3DocumentStore;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.model.PatientNotesResponse;
import org.ncg.notes.common.port.DocumentStorePort;
import org.ncg.notes.common.port.SearchIndexPort;
import org.ncg.notes.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS Lambda handler for GET /api/v1/patients/{patientId}/notes
 * Implements lazy indexing / read-through pattern:
 * 1. Checks warm index (OpenSearch). If present -> serve immediately.
 * 2. If index empty for patient -> scan S3, bulk re-index, and serve with servedFrom: S3_REHYDRATED.
 * Reference implementation: open endpoint (no authcn/authzn required).
 */
public class NotesQueryHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LoggerFactory.getLogger(NotesQueryHandler.class);

    private final SearchIndexPort searchIndexPort;
    private final DocumentStorePort documentStorePort;

    public NotesQueryHandler() {
        String regionStr = System.getenv().getOrDefault("AWS_REGION_NAME", "ap-south-1");
        Region region = Region.of(regionStr);

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

    public NotesQueryHandler(SearchIndexPort searchIndexPort, DocumentStorePort documentStorePort) {
        this.searchIndexPort = searchIndexPort;
        this.documentStorePort = documentStorePort;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        log.info("Received Query Request for patient notes");

        if (event == null) {
            return buildResponse(400, Map.of("error", "Invalid HTTP event"));
        }

        try {
            // Extract patientId from path parameter or raw path
            String patientId = extractPatientId(event);
            if (patientId == null || patientId.isBlank()) {
                return buildResponse(400, Map.of("error", "patientId is required in path: /api/v1/patients/{patientId}/notes"));
            }

            // Resolve tenantId
            String tenantId = resolveTenant(event);

            // Parse pagination
            int page = parseQueryInt(event, "page", 1);
            int limit = parseQueryInt(event, "limit", 20);

            // Step 1: Query OpenSearch index directly (single round-trip for count + hits)
            PatientNotesResponse response = searchIndexPort.findByPatient(tenantId, patientId, page, limit, "INDEX");

            if (response.totalNotes() > 0) {
                log.info("Serving {} notes for patient {} directly from warm INDEX", response.totalNotes(), patientId);
                return buildResponse(200, response);
            }

            // Step 2: Index empty -> trigger lazy rehydration from durable S3 storage (for single tenant)
            if (!"ALL".equalsIgnoreCase(tenantId)) {
                log.info("Patient {} has 0 notes in index. Triggering lazy rehydration from S3...", patientId);
                List<MaskedNote> s3Notes = documentStorePort.fetchAllByPatient(tenantId, patientId);

                if (!s3Notes.isEmpty()) {
                    searchIndexPort.bulkIndex(s3Notes);
                    log.info("Successfully rehydrated {} notes into OpenSearch for patient {}", s3Notes.size(), patientId);
                    PatientNotesResponse rehydratedResponse = searchIndexPort.findByPatient(tenantId, patientId, page, limit, "S3_REHYDRATED");
                    return buildResponse(200, rehydratedResponse);
                }
            }

            PatientNotesResponse emptyResponse = PatientNotesResponse.of(
                patientId, tenantId, 0, page, limit, "NONE", Collections.emptyList()
            );
            return buildResponse(200, emptyResponse);

        } catch (Exception e) {
            log.error("Unhandled error querying patient notes: {}", e.getMessage(), e);
            return buildResponse(500, Map.of("error", "Internal Server Error: " + e.getMessage()));
        }
    }

    private String extractPatientId(APIGatewayV2HTTPEvent event) {
        if (event.getPathParameters() != null && event.getPathParameters().containsKey("patientId")) {
            return event.getPathParameters().get("patientId");
        }
        String rawPath = event.getRawPath();
        if (rawPath != null) {
            String[] segments = rawPath.split("/");
            for (int i = 0; i < segments.length - 1; i++) {
                if ("patients".equalsIgnoreCase(segments[i]) && i + 1 < segments.length) {
                    return segments[i + 1];
                }
            }
        }
        return null;
    }

    private String resolveTenant(APIGatewayV2HTTPEvent event) {
        String tenant = getHeader(event, "x-tenant-id");

        // If a specific tenant is explicitly requested (e.g. TMH-MUMBAI, APOLLO-BLR, HOSP-WEST), ALWAYS query that tenant only!
        if (tenant != null && !tenant.isBlank()) {
            String trimmedTenant = tenant.trim();
            if (!"ALL".equalsIgnoreCase(trimmedTenant)) {
                return trimmedTenant;
            }
            // Tenant is explicitly "ALL": federated view across all hospitals
            return "ALL";
        }

        // Query string parameter check
        if (event.getQueryStringParameters() != null) {
            if (event.getQueryStringParameters().containsKey("tenantId")) {
                String qTenant = event.getQueryStringParameters().get("tenantId").trim();
                if (!"ALL".equalsIgnoreCase(qTenant)) {
                    return qTenant;
                }
                return "ALL";
            }
            if ("true".equalsIgnoreCase(event.getQueryStringParameters().get("crossTenant"))) {
                return "ALL";
            }
        }

        // If no tenant header is provided at all, but a consent artefact is presented with crossTenant intent:
        String consent = getHeader(event, "x-consent-artefact-id");
        if (consent != null && !consent.isBlank()) {
            return "ALL";
        }

        return "DEFAULT_TENANT";
    }

    private String getHeader(APIGatewayV2HTTPEvent event, String headerName) {
        if (event.getHeaders() != null) {
            for (Map.Entry<String, String> entry : event.getHeaders().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(headerName)) {
                    return entry.getValue().trim();
                }
            }
        }
        return null;
    }

    private int parseQueryInt(APIGatewayV2HTTPEvent event, String param, int defaultValue) {
        if (event.getQueryStringParameters() != null && event.getQueryStringParameters().containsKey(param)) {
            try {
                return Integer.parseInt(event.getQueryStringParameters().get(param));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
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
