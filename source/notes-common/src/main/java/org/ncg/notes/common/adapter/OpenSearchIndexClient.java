package org.ncg.notes.common.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.model.NoteSummary;
import org.ncg.notes.common.model.PatientNotesResponse;
import org.ncg.notes.common.port.SearchIndexPort;
import org.ncg.notes.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenSearch REST client implementation of SearchIndexPort.
 * Compatible with OpenSearch 2.x and Elasticsearch 7.x/8.x.
 */
public class OpenSearchIndexClient implements SearchIndexPort {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndexClient.class);

    private final String endpoint;
    private final String indexName;
    private final HttpClient httpClient;

    public OpenSearchIndexClient(String endpoint, String indexName) {
        this.endpoint = sanitizeEndpoint(endpoint);
        this.indexName = (indexName != null && !indexName.isBlank()) ? indexName.trim() : "clinical-notes-v1";
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public OpenSearchIndexClient(String endpoint, String indexName, HttpClient httpClient) {
        this.endpoint = sanitizeEndpoint(endpoint);
        this.indexName = (indexName != null && !indexName.isBlank()) ? indexName.trim() : "clinical-notes-v1";
        this.httpClient = httpClient;
    }

    @Override
    public void indexNote(MaskedNote note) {
        if (note == null || note.noteId() == null) {
            return;
        }
        ensureIndexExists();

        String uri = String.format("%s/%s/_doc/%s?refresh=true", endpoint, indexName, note.noteId());
        String payload = JsonUtil.toJson(note);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(5))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Indexed note {} into OpenSearch index {}", note.noteId(), indexName);
            } else {
                log.error("Failed to index note {}. Status: {}, Response: {}", note.noteId(), response.statusCode(), response.body());
                throw new RuntimeException("OpenSearch index failed with status: " + response.statusCode());
            }
        } catch (Exception e) {
            log.error("Error indexing note {} in OpenSearch: {}", note.noteId(), e.getMessage());
            throw new RuntimeException("OpenSearch index error: " + e.getMessage(), e);
        }
    }

    @Override
    public void bulkIndex(List<MaskedNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return;
        }
        ensureIndexExists();

        StringBuilder ndjson = new StringBuilder();
        for (MaskedNote note : notes) {
            ndjson.append(String.format("{\"index\":{\"_index\":\"%s\",\"_id\":\"%s\"}}\n", indexName, note.noteId()));
            ndjson.append(JsonUtil.toJson(note)).append("\n");
        }

        String uri = String.format("%s/_bulk?refresh=true", endpoint);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(ndjson.toString()))
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Successfully bulk indexed {} notes into OpenSearch", notes.size());
            } else {
                log.warn("Bulk index returned status {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Error during bulk index in OpenSearch: {}", e.getMessage());
        }
    }

    @Override
    public long countByPatient(String tenantId, String patientId) {
        String uri = String.format("%s/%s/_count", endpoint, indexName);
        String filterPart = buildFilter(tenantId, patientId);
        String query = String.format("""
            {
              "query": {
                "bool": {
                  "filter": [
                    %s
                  ]
                }
              }
            }
            """, filterPart);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .timeout(Duration.ofSeconds(3))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = JsonUtil.mapper().readTree(response.body());
                return root.path("count").asLong(0);
            }
        } catch (Exception e) {
            log.warn("Failed to check count for patient {} in OpenSearch: {}", patientId, e.getMessage());
        }

        return 0;
    }

    @Override
    public PatientNotesResponse findByPatient(String tenantId, String patientId, int page, int limit, String servedFrom) {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(100, limit));
        int from = (safePage - 1) * safeLimit;

        String uri = String.format("%s/%s/_search", endpoint, indexName);
        String filterPart = buildFilter(tenantId, patientId);
        String query = String.format("""
            {
              "from": %d,
              "size": %d,
              "query": {
                "bool": {
                  "filter": [
                    %s
                  ]
                }
              },
              "sort": [
                { "timestamps.authoredAt": { "order": "desc", "unmapped_type": "date" } }
              ],
              "_source": {
                "excludes": ["content.rawText"]
              }
            }
            """, from, safeLimit, filterPart);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .timeout(Duration.ofSeconds(5))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = JsonUtil.mapper().readTree(response.body());
                long total = root.path("hits").path("total").path("value").asLong(0);
                JsonNode hits = root.path("hits").path("hits");

                List<NoteSummary> summaries = new ArrayList<>();
                for (JsonNode hit : hits) {
                    JsonNode source = hit.path("_source");
                    MaskedNote note = JsonUtil.mapper().treeToValue(source, MaskedNote.class);
                    if (note != null) {
                        String storageUri = String.format("/api/v1/notes/%s/document", note.noteId());
                        summaries.add(NoteSummary.fromMaskedNote(note, storageUri));
                    }
                }

                return PatientNotesResponse.of(patientId, tenantId, total, safePage, safeLimit, servedFrom, summaries);
            }
        } catch (Exception e) {
            log.error("Failed to query OpenSearch for patient {}: {}", patientId, e.getMessage());
        }

        return PatientNotesResponse.of(patientId, tenantId, 0, safePage, safeLimit, servedFrom, Collections.emptyList());
    }

    @Override
    public long evictPatient(String tenantId, String patientId) {
        String uri = String.format("%s/%s/_delete_by_query?conflicts=proceed&refresh=true", endpoint, indexName);
        String query = String.format("""
            {
              "query": {
                "bool": {
                  "filter": [
                    { "term": { "tenantId": "%s" } },
                    { "term": { "patientId": "%s" } }
                  ]
                }
              }
            }
            """, escape(tenantId), escape(patientId));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = JsonUtil.mapper().readTree(response.body());
                long deleted = root.path("deleted").asLong(0);
                log.info("Evicted {} notes from OpenSearch for tenant={} patient={}", deleted, tenantId, patientId);
                return deleted;
            } else {
                log.warn("Delete by query returned status {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Error evicting patient from OpenSearch: {}", e.getMessage());
        }

        return 0;
    }

    @Override
    public MaskedNote findNoteById(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            return null;
        }
        String uri = String.format("%s/%s/_doc/%s", endpoint, indexName, noteId.trim());
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = JsonUtil.mapper().readTree(response.body());
                JsonNode source = root.path("_source");
                if (!source.isMissingNode()) {
                    return JsonUtil.mapper().treeToValue(source, MaskedNote.class);
                }
            } else if (response.statusCode() != 404) {
                log.warn("findNoteById returned status {} for noteId {}", response.statusCode(), noteId);
            }
        } catch (Exception e) {
            log.error("Error fetching note {} from OpenSearch: {}", noteId, e.getMessage());
        }
        return null;
    }

    private void ensureIndexExists() {
        try {
            String checkUri = String.format("%s/%s", endpoint, indexName);
            HttpRequest headReq = HttpRequest.newBuilder()
                .uri(URI.create(checkUri))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(2))
                .build();

            HttpResponse<Void> headResp = httpClient.send(headReq, HttpResponse.BodyHandlers.discarding());
            if (headResp.statusCode() == 404) {
                String mappings = """
                    {
                      "settings": {
                        "number_of_shards": 1,
                        "number_of_replicas": 0
                      },
                      "mappings": {
                        "properties": {
                          "noteId": { "type": "keyword" },
                          "tenantId": { "type": "keyword" },
                          "patientId": { "type": "keyword" },
                          "sourceSystem": { "type": "keyword" },
                          "noteType": { "type": "keyword" },
                          "timestamps": {
                            "properties": {
                              "authoredAt": { "type": "date" },
                              "recordedAt": { "type": "date" }
                            }
                          },
                          "summary": { "type": "text" },
                          "content": {
                            "properties": {
                              "title": { "type": "text" },
                              "rawText": { "type": "text" }
                            }
                          }
                        }
                      }
                    }
                    """;

                HttpRequest putReq = HttpRequest.newBuilder()
                    .uri(URI.create(checkUri))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mappings))
                    .timeout(Duration.ofSeconds(5))
                    .build();

                httpClient.send(putReq, HttpResponse.BodyHandlers.discarding());
                log.info("Created OpenSearch index {}", indexName);
            }
        } catch (Exception e) {
            log.debug("Index bootstrap check note: {}", e.getMessage());
        }
    }

    String buildFilter(String tenantId, String patientId) {
        boolean isAllTenants = tenantId == null || "ALL".equalsIgnoreCase(tenantId) || "*".equals(tenantId);
        boolean isAbha = patientId != null && (patientId.startsWith("14-") || patientId.contains("@abdm") || patientId.matches("\\d{2}-\\d{4}-\\d{4}-\\d{4}"));

        StringBuilder sb = new StringBuilder();
        if (!isAllTenants) {
            sb.append(String.format("{\"term\":{\"tenantId\":\"%s\"}},", escape(tenantId)));
        }

        if (isAbha) {
            String abhaHash = "[PHI:ABHA:" + hashAbha(patientId) + "]";
            sb.append(String.format("""
                {
                  "bool": {
                    "should": [
                      { "term": { "demographics.abhaId.keyword": "%s" } },
                      { "term": { "demographics.abhaId.keyword": "%s" } },
                      { "term": { "patientId": "%s" } }
                    ],
                    "minimum_should_match": 1
                  }
                }
                """, escape(patientId), escape(abhaHash), escape(patientId)));
        } else {
            sb.append(String.format("{\"term\":{\"patientId\":\"%s\"}}", escape(patientId)));
        }
        return sb.toString();
    }

    String hashAbha(String abha) {
        if (abha == null || abha.isBlank()) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(abha.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 6);
        } catch (Exception e) {
            return "";
        }
    }

    private String sanitizeEndpoint(String ep) {
        if (ep == null || ep.isBlank()) {
            return "http://localhost:9200";
        }
        String clean = ep.trim();
        return clean.endsWith("/") ? clean.substring(0, clean.length() - 1) : clean;
    }

    private String escape(String input) {
        if (input == null) return "";
        return input.replace("\"", "\\\"");
    }
}
