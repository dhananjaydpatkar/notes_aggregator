package org.ncg.notes.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.model.NoteSummary;
import org.ncg.notes.common.model.PatientNotesResponse;
import org.ncg.notes.common.port.DocumentStorePort;
import org.ncg.notes.common.port.SearchIndexPort;

@ExtendWith(MockitoExtension.class)
class NotesQueryHandlerTest {

    @Mock
    private SearchIndexPort searchIndexPort;

    @Mock
    private DocumentStorePort documentStorePort;

    private NotesQueryHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NotesQueryHandler(searchIndexPort, documentStorePort);
    }

    @Test
    void shouldServeDirectlyFromIndexWhenCountIsGreaterThanZero() {
        PatientNotesResponse mockResponse = PatientNotesResponse.of(
            "PAT-100", "HOSP-WEST", 5, 1, 20, "INDEX",
            List.of(new NoteSummary("NOTE-1", "PROGRESS", null, null, null, "MOIS", "F-1", "Summary", "/uri/1"))
        );
        when(searchIndexPort.findByPatient("HOSP-WEST", "PAT-100", 1, 20, "INDEX"))
            .thenReturn(mockResponse);

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withPathParameters(Map.of("patientId", "PAT-100"))
            .withHeaders(Map.of("X-Tenant-Id", "HOSP-WEST"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("INDEX", "PAT-100", "NOTE-1");

        verify(documentStorePort, never()).fetchAllByPatient(anyString(), anyString());
        verify(searchIndexPort, never()).bulkIndex(any());
    }

    @Test
    void shouldTriggerLazyRehydrationFromS3WhenIndexIsEmpty() {
        PatientNotesResponse emptyIndexResponse = PatientNotesResponse.of(
            "PAT-100", "HOSP-WEST", 0, 1, 20, "INDEX", Collections.emptyList()
        );
        when(searchIndexPort.findByPatient("HOSP-WEST", "PAT-100", 1, 20, "INDEX"))
            .thenReturn(emptyIndexResponse);

        MaskedNote note1 = MaskedNote.builder()
            .noteId("NOTE-S3-1")
            .tenantId("HOSP-WEST")
            .patientId("PAT-100")
            .noteType("HISTOPATHOLOGY")
            .build();

        when(documentStorePort.fetchAllByPatient("HOSP-WEST", "PAT-100"))
            .thenReturn(List.of(note1));

        PatientNotesResponse mockResponse = PatientNotesResponse.of(
            "PAT-100", "HOSP-WEST", 1, 1, 20, "S3_REHYDRATED",
            List.of(new NoteSummary("NOTE-S3-1", "HISTOPATHOLOGY", null, null, null, "LAB", "F-1", "Biopsy report", "/uri/1"))
        );
        when(searchIndexPort.findByPatient("HOSP-WEST", "PAT-100", 1, 20, "S3_REHYDRATED"))
            .thenReturn(mockResponse);

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withPathParameters(Map.of("patientId", "PAT-100"))
            .withHeaders(Map.of("X-Tenant-Id", "HOSP-WEST"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("S3_REHYDRATED", "NOTE-S3-1");

        verify(documentStorePort).fetchAllByPatient("HOSP-WEST", "PAT-100");
        verify(searchIndexPort).bulkIndex(List.of(note1));
    }

    @Test
    void shouldEnableCrossTenantFederationWhenConsentArtefactIsProvided() {
        // Patient PAT-ABDM-001 has notes across 3 different hospital tenants:
        // TMH-MUMBAI (Surgery), APOLLO-BLR (Chemotherapy), AIIMS-DEL (Radiotherapy)
        List<NoteSummary> federatedNotes = List.of(
            new NoteSummary("NOTE-TMH-01", "OPERATIVE_NOTE", null, null, null, "MOIS", "FAC-TMH-01", "Mastectomy", "/uri/tmh1", "TMH-MUMBAI"),
            new NoteSummary("NOTE-APOLLO-01", "MEDICAL_ONCOLOGY_PROGRESS", null, null, null, "HIMS", "FAC-APL-02", "AC-T Cycle 3", "/uri/apl1", "APOLLO-BLR"),
            new NoteSummary("NOTE-AIIMS-01", "RADIATION_ONCOLOGY_NOTE", null, null, null, "AIIMS-EHR", "FAC-AIM-01", "3D-CRT complete", "/uri/aim1", "AIIMS-DEL")
        );

        PatientNotesResponse federatedResponse = PatientNotesResponse.of(
            "PAT-ABDM-001", "ALL", 3, 1, 20, "INDEX", federatedNotes
        );

        // ABDM Consent Artefact triggers cross-tenant query: tenantId = "ALL"
        when(searchIndexPort.findByPatient("ALL", "PAT-ABDM-001", 1, 20, "INDEX"))
            .thenReturn(federatedResponse);

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withPathParameters(Map.of("patientId", "PAT-ABDM-001"))
            .withHeaders(Map.of(
                "X-Consent-Artefact-Id", "CONSENT-ABDM-9901-TATA-APOLLO",
                "X-Tenant-Id", "ALL" // When user explicitly queries ALL with consent
            ))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("TMH-MUMBAI");
        assertThat(response.getBody()).contains("APOLLO-BLR");
        assertThat(response.getBody()).contains("AIIMS-DEL");
        assertThat(response.getBody()).contains("totalNotes\":3");

        // Verify that search was executed with tenantId = "ALL"
        verify(searchIndexPort).findByPatient("ALL", "PAT-ABDM-001", 1, 20, "INDEX");
        // Verify no single-tenant S3 rehydration was attempted
        verify(documentStorePort, never()).fetchAllByPatient(anyString(), anyString());
    }

    @Test
    void shouldHonorSpecificTenantEvenWhenConsentArtefactIsPresent() {
        PatientNotesResponse isolatedResponse = PatientNotesResponse.of(
            "PAT-ABDM-001", "TMH-MUMBAI", 1, 1, 20, "INDEX",
            List.of(new NoteSummary("NOTE-TMH-01", "OPERATIVE_NOTE", null, null, null, "MOIS", "FAC-TMH-01", "Mastectomy", "/uri/tmh1", "TMH-MUMBAI"))
        );

        when(searchIndexPort.findByPatient("TMH-MUMBAI", "PAT-ABDM-001", 1, 20, "INDEX"))
            .thenReturn(isolatedResponse);

        // When requesting specifically for TMH-MUMBAI, even if consent artefact is presented, query must only return TMH-MUMBAI
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withPathParameters(Map.of("patientId", "PAT-ABDM-001"))
            .withHeaders(Map.of(
                "X-Tenant-Id", "TMH-MUMBAI",
                "X-Consent-Artefact-Id", "LOCAL-EHR-TMH"
            ))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("TMH-MUMBAI");
        verify(searchIndexPort).findByPatient("TMH-MUMBAI", "PAT-ABDM-001", 1, 20, "INDEX");
    }

    @Test
    void shouldEnforceTenantIsolationWhenConsentArtefactIsAbsent() {
        // Without consent artefact, hospital can only see its own tenant's notes
        PatientNotesResponse isolatedResponse = PatientNotesResponse.of(
            "PAT-ABDM-001", "TMH-MUMBAI", 1, 1, 20, "INDEX",
            List.of(new NoteSummary("NOTE-TMH-01", "OPERATIVE_NOTE", null, null, null, "MOIS", "FAC-TMH-01", "Mastectomy", "/uri/tmh1", "TMH-MUMBAI"))
        );

        when(searchIndexPort.findByPatient("TMH-MUMBAI", "PAT-ABDM-001", 1, 20, "INDEX"))
            .thenReturn(isolatedResponse);

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withPathParameters(Map.of("patientId", "PAT-ABDM-001"))
            .withHeaders(Map.of("X-Tenant-Id", "TMH-MUMBAI"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("TMH-MUMBAI");
        assertThat(response.getBody()).doesNotContain("APOLLO-BLR");

        // Verify query was strictly scoped to TMH-MUMBAI
        verify(searchIndexPort).findByPatient("TMH-MUMBAI", "PAT-ABDM-001", 1, 20, "INDEX");
        verify(searchIndexPort, never()).findByPatient(eq("ALL"), anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    void shouldEnableCrossTenantFederationWhenQueryParamCrossTenantIsTrue() {
        PatientNotesResponse crossResponse = PatientNotesResponse.of(
            "PAT-ABDM-001", "ALL", 2, 1, 20, "INDEX",
            List.of(
                new NoteSummary("N-1", "NOTE", null, null, null, "S", "F", "Sum", "/u/1", "HOSP-A"),
                new NoteSummary("N-2", "NOTE", null, null, null, "S", "F", "Sum", "/u/2", "HOSP-B")
            )
        );

        when(searchIndexPort.findByPatient("ALL", "PAT-ABDM-001", 1, 20, "INDEX"))
            .thenReturn(crossResponse);

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withPathParameters(Map.of("patientId", "PAT-ABDM-001"))
            .withQueryStringParameters(Map.of("crossTenant", "true"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        verify(searchIndexPort).findByPatient("ALL", "PAT-ABDM-001", 1, 20, "INDEX");
    }

    @Test
    void shouldSupportAbhaIdAsPatientIdentifierInPathWithConsent() {
        String abhaId = "14-8765-4321-9876";
        PatientNotesResponse abhaResponse = PatientNotesResponse.of(
            abhaId, "ALL", 3, 1, 20, "INDEX",
            List.of(new NoteSummary("N-1", "NOTE", null, null, null, "S", "F", "Sum", "/u/1", "TMH-MUMBAI"))
        );

        when(searchIndexPort.findByPatient("ALL", abhaId, 1, 20, "INDEX"))
            .thenReturn(abhaResponse);

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withPathParameters(Map.of("patientId", abhaId))
            .withHeaders(Map.of("X-Consent-Artefact-Id", "CONSENT-ABDM-9901"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        verify(searchIndexPort).findByPatient("ALL", abhaId, 1, 20, "INDEX");
    }
}
