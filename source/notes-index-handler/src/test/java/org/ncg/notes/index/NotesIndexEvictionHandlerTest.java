package org.ncg.notes.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ncg.notes.common.port.SearchIndexPort;

@ExtendWith(MockitoExtension.class)
class NotesIndexEvictionHandlerTest {

    @Mock
    private SearchIndexPort searchIndexPort;

    private NotesIndexEvictionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NotesIndexEvictionHandler(searchIndexPort);
    }

    @Test
    void shouldSuccessfullyEvictPatientFromIndex() {
        when(searchIndexPort.evictPatient("HOSP-WEST", "PAT-100")).thenReturn(12L);

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withPathParameters(Map.of("patientId", "PAT-100"))
            .withHeaders(Map.of("X-Tenant-Id", "HOSP-WEST"))
            .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("PAT-100", "HOSP-WEST", "\"deletedFromIndex\":12", "\"dataRetainedInS3\":true");

        verify(searchIndexPort).evictPatient("HOSP-WEST", "PAT-100");
    }
}
