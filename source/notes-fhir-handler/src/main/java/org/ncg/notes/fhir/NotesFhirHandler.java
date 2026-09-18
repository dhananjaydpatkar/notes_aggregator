package org.ncg.notes.fhir;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.ncg.notes.common.adapter.DynamoDbIdempotencyStore;
import org.ncg.notes.common.adapter.OpenSearchIndexClient;
import org.ncg.notes.common.adapter.S3DocumentStore;
import org.ncg.notes.common.model.Author;
import org.ncg.notes.common.model.ClinicalNote;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.model.NoteSummary;
import org.ncg.notes.common.model.PatientNotesResponse;
import org.ncg.notes.common.model.Timestamps;
import org.ncg.notes.fhir.mapper.NoteToFhirMapper;
import org.ncg.notes.fhir.model.FhirBundle;
import org.ncg.notes.fhir.model.FhirCapabilityStatement;
import org.ncg.notes.fhir.model.FhirDocumentReference;
import org.ncg.notes.fhir.model.FhirOperationOutcome;
import org.ncg.notes.phi.IndianAndHipaaPhiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS Lambda Handler for FHIR R4 DocumentReference facade.
 *
 * Routes:
 *   GET  /fhir/r4/metadata                           -> CapabilityStatement
 *   GET  /fhir/r4/DocumentReference?patient=PAT-xxx  -> Bundle (searchset)
 *   GET  /fhir/r4/DocumentReference/{id}             -> DocumentReference
 *   POST /fhir/r4/DocumentReference                  -> ingest note, return DocumentReference
 *   GET  /fhir/r4/Patient/{id}/$everything           -> Bundle (all notes)
 */
public class NotesFhirHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger log = LoggerFactory.getLogger(NotesFhirHandler.class);
    private static final String FHIR_JSON = "application/fhir+json";
    private static final String DEFAULT_TENANT = "DEFAULT_TENANT";

    private final ObjectMapper json;
    private final OpenSearchIndexClient searchIndex;
    private final S3DocumentStore documentStore;
    private final DynamoDbIdempotencyStore idempotencyStore;
    private final IndianAndHipaaPhiMasker phiMasker;
    private final NoteToFhirMapper fhirMapper;

    public NotesFhirHandler() {
        this.json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String osEndpoint  = env("OPENSEARCH_ENDPOINT", "http://localhost:9200");
        String osIndex     = env("OPENSEARCH_INDEX",    "clinical-notes-v1");
        String s3Bucket    = env("S3_BUCKET_NAME",      "clinical-notes-local");
        String s3Endpoint  = env("S3_ENDPOINT",         "");
        String dynTable    = env("DYNAMODB_TABLE_NAME",  "notes-idempotency-local");
        String dynEndpoint = env("DYNAMODB_ENDPOINT",    "");
        String region      = env("AWS_REGION_NAME",      "ap-south-1");

        this.searchIndex = new OpenSearchIndexClient(osEndpoint, osIndex);

        S3Client s3Client;
        DynamoDbClient dynClient;
        if (!s3Endpoint.isBlank() || !dynEndpoint.isBlank()) {
            var creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("mock", "mock"));
            s3Client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(creds)
                .endpointOverride(URI.create(!s3Endpoint.isBlank() ? s3Endpoint : "http://localhost:4566"))
                .forcePathStyle(true)
                .build();
            dynClient = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(creds)
                .endpointOverride(URI.create(!dynEndpoint.isBlank() ? dynEndpoint : "http://localhost:8000"))
                .build();
        } else {
            s3Client  = S3Client.builder().region(Region.of(region)).build();
            dynClient = DynamoDbClient.builder().region(Region.of(region)).build();
        }

        this.documentStore     = new S3DocumentStore(s3Client, s3Bucket);
        this.idempotencyStore  = new DynamoDbIdempotencyStore(dynClient, dynTable);
        this.phiMasker         = new IndianAndHipaaPhiMasker();
        this.fhirMapper        = new NoteToFhirMapper();
    }

    // Test constructor
    NotesFhirHandler(OpenSearchIndexClient searchIndex, S3DocumentStore documentStore,
                     DynamoDbIdempotencyStore idempotencyStore, IndianAndHipaaPhiMasker phiMasker) {
        this.json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.searchIndex      = searchIndex;
        this.documentStore    = documentStore;
        this.idempotencyStore = idempotencyStore;
        this.phiMasker        = phiMasker;
        this.fhirMapper       = new NoteToFhirMapper();
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        String path   = event.getRawPath() != null ? event.getRawPath() : "";
        String method = event.getRequestContext() != null
            && event.getRequestContext().getHttp() != null
            ? event.getRequestContext().getHttp().getMethod().toUpperCase()
            : "GET";

        log.info("FHIR {} {}", method, path);
        try {
            if ("GET".equals(method) && "/fhir/r4/metadata".equals(path)) {
                return handleCapabilityStatement();
            }
            if ("GET".equals(method) && "/fhir/r4/DocumentReference".equals(path)) {
                return handleDocumentReferenceSearch(event);
            }
            if ("POST".equals(method) && "/fhir/r4/DocumentReference".equals(path)) {
                return handleDocumentReferenceCreate(event);
            }
            if ("GET".equals(method) && path.matches("/fhir/r4/Patient/[^/]+/\\$everything")) {
                String patientId = path.split("/")[4];
                return handlePatientEverything(patientId, event);
            }
            if ("GET".equals(method) && path.matches("/fhir/r4/DocumentReference/[^/]+")) {
                String noteId = path.substring("/fhir/r4/DocumentReference/".length());
                return handleDocumentReferenceRead(noteId);
            }
            return operationOutcome(404, FhirOperationOutcome.error("not-found",
                "Unknown FHIR endpoint: " + method + " " + path));
        } catch (Exception e) {
            log.error("FHIR error {}: {}", path, e.getMessage(), e);
            return operationOutcome(500, FhirOperationOutcome.error("exception", e.getMessage()));
        }
    }

    // ─── Route Handlers ──────────────────────────────────────────────────────

    private APIGatewayV2HTTPResponse handleCapabilityStatement() throws Exception {
        FhirCapabilityStatement cs = FhirCapabilityStatement.defaultCapabilityStatement(Instant.now().toString());
        return fhirResponse(200, json.writeValueAsString(cs));
    }

    private APIGatewayV2HTTPResponse handleDocumentReferenceSearch(APIGatewayV2HTTPEvent event) throws Exception {
        Map<String, String> qp = queryParams(event);
        String tenantId  = header(event, "X-Tenant-Id", DEFAULT_TENANT);
        String patientId = qp.get("patient");

        if (patientId == null) {
            // Support patient.identifier=https://abdm.gov.in/abha|<abha-number>
            String pi = qp.get("patient.identifier");
            if (pi != null && pi.contains("|")) {
                patientId = pi.substring(pi.lastIndexOf('|') + 1);
            }
        }
        if (patientId == null || patientId.isBlank()) {
            return operationOutcome(400, FhirOperationOutcome.error("required",
                "Missing required search parameter: patient"));
        }

        int count  = parseIntParam(qp, "_count",  20,  100);
        int offset = parseIntParam(qp, "_offset", 0,  Integer.MAX_VALUE);
        int page   = (count > 0) ? (offset / count) + 1 : 1;

        PatientNotesResponse resp = searchIndex.findByPatient(tenantId, patientId, page, count, "INDEX");

        List<FhirDocumentReference> docs = new ArrayList<>();
        for (NoteSummary ns : resp.notes()) {
            docs.add(fhirMapper.toDocumentReference(ns, tenantId));
        }

        String selfUrl = "/fhir/r4/DocumentReference?patient=" + patientId
            + "&_count=" + count + "&_offset=" + offset;
        FhirBundle bundle = fhirMapper.toBundle(docs, (int) resp.totalNotes(), selfUrl);
        return fhirResponse(200, json.writeValueAsString(bundle));
    }

    private APIGatewayV2HTTPResponse handleDocumentReferenceRead(String noteId) throws Exception {
        MaskedNote note = searchIndex.findNoteById(noteId);
        if (note != null) {
            return fhirResponse(200, json.writeValueAsString(fhirMapper.toDocumentReference(note)));
        }
        return operationOutcome(404, FhirOperationOutcome.notFound("DocumentReference/" + noteId));
    }

    private APIGatewayV2HTTPResponse handleDocumentReferenceCreate(APIGatewayV2HTTPEvent event) throws Exception {
        String body = event.getBody();
        if (body == null || body.isBlank()) {
            return operationOutcome(400, FhirOperationOutcome.error("invalid", "Empty request body"));
        }

        FhirDocumentReference inbound;
        try {
            inbound = json.readValue(body, FhirDocumentReference.class);
        } catch (Exception e) {
            return operationOutcome(400, FhirOperationOutcome.error("invalid",
                "Invalid FHIR DocumentReference JSON: " + e.getMessage()));
        }

        String tenantId  = header(event, "X-Tenant-Id", DEFAULT_TENANT);
        String patientId = extractPatientId(inbound);
        if (patientId == null) {
            return operationOutcome(400, FhirOperationOutcome.error("required",
                "subject.reference (Patient/{id}) is required"));
        }

        String noteType = null;
        if (inbound.type() != null && inbound.type().coding() != null && !inbound.type().coding().isEmpty()) {
            noteType = fhirMapper.loincToNoteType(inbound.type().coding().get(0).code());
        }
        if (noteType == null) noteType = "UNCLASSIFIED";

        String sourceSystem = extractExtension(inbound, "source-system");
        if (sourceSystem == null) sourceSystem = "FHIR_INGEST";

        String facilityId = null;
        if (inbound.context() != null && inbound.context().sourcePatientInfo() != null) {
            facilityId = inbound.context().sourcePatientInfo().display();
        }

        String encounterId = null;
        if (inbound.context() != null && inbound.context().encounter() != null
                && !inbound.context().encounter().isEmpty()) {
            String ref = inbound.context().encounter().get(0).reference();
            encounterId = (ref != null && ref.startsWith("Encounter/"))
                ? ref.substring("Encounter/".length()) : ref;
        }

        String authorId   = null;
        String authorName = null;
        if (inbound.author() != null && !inbound.author().isEmpty()) {
            var authorRef = inbound.author().get(0);
            authorName = authorRef.display();
            if (authorRef.reference() != null && authorRef.reference().startsWith("Practitioner/")) {
                authorId = authorRef.reference().substring("Practitioner/".length());
            }
        }
        if (authorId == null) authorId = "FHIR_AUTHOR";

        Instant authored = inbound.date() != null ? Instant.parse(inbound.date()) : Instant.now();

        ClinicalNote clinicalNote = ClinicalNote.builder()
            .tenantId(tenantId)
            .patientId(patientId)
            .sourceSystem(sourceSystem)
            .facilityId(facilityId)
            .encounterId(encounterId)
            .noteType(noteType)
            .author(new Author(authorId, authorName, null))
            .timestamps(Timestamps.builder()
                .authoredAt(authored)
                .recordedAt(Instant.now())
                .build())
            .build();

        MaskedNote maskedNote = phiMasker.mask(clinicalNote);

        // Idempotency check
        String idempotencyKey = inbound.id() != null
            ? "FHIR:" + tenantId + ":" + inbound.id()
            : null;

        if (idempotencyKey != null) {
            Optional<String> existing = idempotencyStore.findExistingNoteId(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                log.info("FHIR duplicate ingest for key {}", idempotencyKey);
                return fhirResponse(200, json.writeValueAsString(fhirMapper.toDocumentReference(maskedNote)));
            }
        }

        documentStore.storeDocument(maskedNote);
        searchIndex.indexNote(maskedNote);

        if (idempotencyKey != null) {
            idempotencyStore.saveIdempotencyRecord(tenantId, idempotencyKey, maskedNote.noteId(), 86400L);
        }

        return fhirResponse(201, json.writeValueAsString(fhirMapper.toDocumentReference(maskedNote)));
    }

    private APIGatewayV2HTTPResponse handlePatientEverything(String patientId, APIGatewayV2HTTPEvent event) throws Exception {
        String tenantId = header(event, "X-Tenant-Id", DEFAULT_TENANT);
        int count = parseIntParam(queryParams(event), "_count", 100, 500);

        PatientNotesResponse resp = searchIndex.findByPatient(tenantId, patientId, 1, count, "INDEX");

        List<FhirDocumentReference> docs = new ArrayList<>();
        for (NoteSummary ns : resp.notes()) {
            docs.add(fhirMapper.toDocumentReference(ns, tenantId));
        }

        FhirBundle bundle = fhirMapper.toBundle(docs, (int) resp.totalNotes(),
            "/fhir/r4/Patient/" + patientId + "/$everything");
        return fhirResponse(200, json.writeValueAsString(bundle));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private APIGatewayV2HTTPResponse fhirResponse(int status, String body) {
        return APIGatewayV2HTTPResponse.builder()
            .withStatusCode(status)
            .withHeaders(corsHeaders())
            .withBody(body)
            .build();
    }

    private APIGatewayV2HTTPResponse operationOutcome(int status, FhirOperationOutcome outcome) {
        try {
            return fhirResponse(status, json.writeValueAsString(outcome));
        } catch (Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(500)
                .withHeaders(corsHeaders())
                .withBody("{\"resourceType\":\"OperationOutcome\"}")
                .build();
        }
    }

    private Map<String, String> corsHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", FHIR_JSON);
        h.put("Access-Control-Allow-Origin",  "*");
        h.put("Access-Control-Allow-Headers", "Content-Type,X-Tenant-Id,Idempotency-Key");
        h.put("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
        return h;
    }

    private Map<String, String> queryParams(APIGatewayV2HTTPEvent event) {
        Map<String, String> qp = event.getQueryStringParameters();
        return qp != null ? qp : Collections.emptyMap();
    }

    private String header(APIGatewayV2HTTPEvent event, String name, String def) {
        if (event.getHeaders() == null) return def;
        String v = event.getHeaders().get(name);
        if (v == null) v = event.getHeaders().get(name.toLowerCase());
        return (v != null && !v.isBlank()) ? v.trim() : def;
    }

    private int parseIntParam(Map<String, String> qp, String key, int def, int max) {
        String v = qp.get(key);
        if (v == null) return def;
        try { return Math.min(max, Math.max(0, Integer.parseInt(v.trim()))); }
        catch (NumberFormatException e) { return def; }
    }

    private String extractPatientId(FhirDocumentReference doc) {
        if (doc.subject() == null) return null;
        String ref = doc.subject().reference();
        if (ref != null && ref.startsWith("Patient/")) return ref.substring("Patient/".length());
        return doc.subject().display();
    }

    private String extractExtension(FhirDocumentReference doc, String suffix) {
        if (doc.extension() == null) return null;
        return doc.extension().stream()
            .filter(e -> e.url() != null && e.url().endsWith(suffix))
            .map(e -> e.valueString())
            .findFirst().orElse(null);
    }

    private String env(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v.trim() : def;
    }
}
