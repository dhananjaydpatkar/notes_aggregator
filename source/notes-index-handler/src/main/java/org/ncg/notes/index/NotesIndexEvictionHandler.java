package org.ncg.notes.index;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import java.util.Map;
import org.ncg.notes.common.adapter.OpenSearchIndexClient;
import org.ncg.notes.common.model.IndexEvictionResponse;
import org.ncg.notes.common.port.SearchIndexPort;
import org.ncg.notes.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AWS Lambda handler for DELETE /api/v1/patients/{patientId}/index
 * Evicts patient's documents from warm OpenSearch index without touching durable S3 storage.
 * Reference implementation: open endpoint (no authcn/authzn required).
 */
public class NotesIndexEvictionHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LoggerFactory.getLogger(NotesIndexEvictionHandler.class);

    private final SearchIndexPort searchIndexPort;

    public NotesIndexEvictionHandler() {
        String osEndpoint = System.getenv().getOrDefault("OPENSEARCH_ENDPOINT", "http://localhost:9200");
        String osIndex = System.getenv().getOrDefault("OPENSEARCH_INDEX", "clinical-notes-v1");
        this.searchIndexPort = new OpenSearchIndexClient(osEndpoint, osIndex);
    }

    public NotesIndexEvictionHandler(SearchIndexPort searchIndexPort) {
        this.searchIndexPort = searchIndexPort;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        log.info("Received Index Eviction Request");

        if (event == null) {
            return buildResponse(400, Map.of("error", "Invalid HTTP event"));
        }

        try {
            // Extract patientId from path
            String patientId = extractPatientId(event);
            if (patientId == null || patientId.isBlank()) {
                return buildResponse(400, Map.of("error", "patientId is required in path: /api/v1/patients/{patientId}/index"));
            }

            // Resolve tenantId
            String tenantId = resolveTenant(event);

            // Execute eviction from OpenSearch
            long deletedCount = searchIndexPort.evictPatient(tenantId, patientId);
            log.info("Successfully evicted {} notes from index for patient {} (tenant={})", deletedCount, patientId, tenantId);

            IndexEvictionResponse response = IndexEvictionResponse.success(patientId, tenantId, deletedCount);
            return buildResponse(200, response);

        } catch (Exception e) {
            log.error("Unhandled error during index eviction: {}", e.getMessage(), e);
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
        if (event.getHeaders() != null) {
            for (Map.Entry<String, String> entry : event.getHeaders().entrySet()) {
                if (entry.getKey().equalsIgnoreCase("x-tenant-id")) {
                    return entry.getValue().trim();
                }
            }
        }
        if (event.getQueryStringParameters() != null && event.getQueryStringParameters().containsKey("tenantId")) {
            return event.getQueryStringParameters().get("tenantId").trim();
        }
        return "DEFAULT_TENANT";
    }

    private APIGatewayV2HTTPResponse buildResponse(int statusCode, Object body) {
        return APIGatewayV2HTTPResponse.builder()
            .withStatusCode(statusCode)
            .withHeaders(Map.of(
                "Content-Type", "application/json",
                "Access-Control-Allow-Origin", "*",
                "Access-Control-Allow-Headers", "Content-Type,X-Tenant-Id,Idempotency-Key",
                "Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS"
            ))
            .withBody(JsonUtil.toJson(body))
            .build();
    }
}
