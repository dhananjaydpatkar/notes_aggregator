package org.ncg.notes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ncg.notes.common.model.Author;
import org.ncg.notes.common.model.ClinicalNote;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.port.DocumentStorePort;
import org.ncg.notes.common.port.IdempotencyPort;
import org.ncg.notes.common.port.PhiMaskingPort;
import org.ncg.notes.common.port.SearchIndexPort;
import org.ncg.notes.common.util.JsonUtil;

@ExtendWith(MockitoExtension.class)
class NotesIngestionHandlerTest {

    @Mock
    private PhiMaskingPort phiMaskingPort;

    @Mock
    private IdempotencyPort idempotencyPort;

    @Mock
    private SearchIndexPort searchIndexPort;

    @Mock
    private DocumentStorePort documentStorePort;

    private NotesIngestionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NotesIngestionHandler(phiMaskingPort, idempotencyPort, searchIndexPort, documentStorePort);
    }

    @Test
    void shouldSuccessfullyIngestNote() {
        ClinicalNote note = ClinicalNote.builder()
            .noteId("NOTE-1")
            .tenantId("HOSP-WEST")
            .patientId("PAT-123")
            .noteType("MEDICAL_ONCOLOGY_PROGRESS")
            .author(Author.builder().clinicianId("DOC-1").name("Dr. Sen").build())
            .build();

        MaskedNote masked = MaskedNote.builder()
            .noteId(note.noteId())
            .tenantId(note.tenantId())
            .patientId(note.patientId())
            .noteType(note.noteType())
            .build();

        when(phiMaskingPort.mask(any())).thenReturn(masked);
        when(documentStorePort.storeDocument(any())).thenReturn("s3://clinical-notes-store/v1/note1.json");

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withBody(JsonUtil.toJson(note))
            .withHeaders(Map.of("X-Tenant-Id", "HOSP-WEST"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.getBody()).contains("NOTE-1", "HOSP-WEST", "PAT-123");

        verify(searchIndexPort).indexNote(masked);
        verify(documentStorePort).storeDocument(masked);
    }

    @Test
    void shouldReturnCachedResponseForDuplicateIdempotencyKey() {
        ClinicalNote note = ClinicalNote.builder()
            .noteId("NOTE-NEW")
            .tenantId("HOSP-WEST")
            .patientId("PAT-123")
            .noteType("MEDICAL_ONCOLOGY_PROGRESS")
            .build();

        when(idempotencyPort.findExistingNoteId("HOSP-WEST", "IDEM-KEY-999"))
            .thenReturn(Optional.of("NOTE-EXISTING-123"));

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withBody(JsonUtil.toJson(note))
            .withHeaders(Map.of("X-Tenant-Id", "HOSP-WEST", "Idempotency-Key", "IDEM-KEY-999"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("NOTE-EXISTING-123");

        verify(searchIndexPort, never()).indexNote(any());
        verify(documentStorePort, never()).storeDocument(any());
    }

    @Test
    void shouldReturn400WhenPatientIdIsMissing() {
        ClinicalNote note = ClinicalNote.builder()
            .noteType("SURGICAL_OPERATIVE")
            .build();

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withBody(JsonUtil.toJson(note))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);
        assertThat(response.getStatusCode()).isEqualTo(400);
        assertThat(response.getBody()).contains("patientId is required");
    }

    @Test
    void shouldSuccessfullyIngestBatchOfNotesInSinglePostCall() {
        // Multi-tenant batch payload for patient PAT-ABDM-001 visiting multiple facilities
        ClinicalNote note1 = ClinicalNote.builder()
            .noteId("NOTE-BATCH-1")
            .tenantId("TMH-MUMBAI")
            .patientId("PAT-ABDM-001")
            .noteType("OPERATIVE_NOTE")
            .author(Author.builder().clinicianId("DOC-1").name("Dr. Sunil Deshmukh").build())
            .build();

        ClinicalNote note2 = ClinicalNote.builder()
            .noteId("NOTE-BATCH-2")
            .tenantId("APOLLO-BLR")
            .patientId("PAT-ABDM-001")
            .noteType("MEDICAL_ONCOLOGY_PROGRESS")
            .author(Author.builder().clinicianId("DOC-2").name("Dr. Rajiv Singhania").build())
            .build();

        ClinicalNote note3 = ClinicalNote.builder()
            .noteId("NOTE-BATCH-3")
            .tenantId("AIIMS-DEL")
            .patientId("PAT-ABDM-001")
            .noteType("RADIATION_ONCOLOGY_NOTE")
            .author(Author.builder().clinicianId("DOC-3").name("Dr. Sanjay Bhatt").build())
            .build();

        List<ClinicalNote> batch = List.of(note1, note2, note3);

        MaskedNote masked1 = MaskedNote.builder().noteId("NOTE-BATCH-1").tenantId("TMH-MUMBAI").patientId("PAT-ABDM-001").build();
        MaskedNote masked2 = MaskedNote.builder().noteId("NOTE-BATCH-2").tenantId("APOLLO-BLR").patientId("PAT-ABDM-001").build();
        MaskedNote masked3 = MaskedNote.builder().noteId("NOTE-BATCH-3").tenantId("AIIMS-DEL").patientId("PAT-ABDM-001").build();

        when(phiMaskingPort.mask(any())).thenReturn(masked1, masked2, masked3);
        when(documentStorePort.storeDocument(any())).thenReturn("s3://clinical-notes-store/v1/note.json");

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withBody(JsonUtil.toJson(batch))
            .withHeaders(Map.of("Content-Type", "application/json"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.getBody()).contains("totalIngested\":3");
        assertThat(response.getBody()).contains("NOTE-BATCH-1", "NOTE-BATCH-2", "NOTE-BATCH-3");

        // Verify bulk indexing and all 3 documents stored to durable storage
        verify(searchIndexPort).bulkIndex(any());
        verify(documentStorePort, org.mockito.Mockito.times(3)).storeDocument(any());
    }

    @Test
    void shouldReturn400WhenBatchPayloadIsEmptyArray() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withBody("[]")
            .withHeaders(Map.of("Content-Type", "application/json"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(400);
        assertThat(response.getBody()).contains("Batch notes list cannot be empty");
    }

    @Test
    void shouldReturn400WhenBatchNoteHasMissingPatientId() {
        ClinicalNote validNote = ClinicalNote.builder()
            .patientId("PAT-123")
            .noteType("OPERATIVE_NOTE")
            .build();

        ClinicalNote invalidNote = ClinicalNote.builder()
            .noteType("LAB_REPORT")
            // missing patientId
            .build();

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withBody(JsonUtil.toJson(List.of(validNote, invalidNote)))
            .withHeaders(Map.of("Content-Type", "application/json"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(400);
        assertThat(response.getBody()).contains("patientId is required");
    }
}
