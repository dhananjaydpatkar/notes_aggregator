package org.ncg.notes.common.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenSearchIndexClientTest {

    private OpenSearchIndexClient client;

    @BeforeEach
    void setUp() {
        client = new OpenSearchIndexClient("http://localhost:9200", "clinical-notes-v1");
    }

    @Test
    void shouldBuildStandardTenantAndPatientFilter() {
        String filter = client.buildFilter("HOSP-WEST", "PAT-9082341");

        assertThat(filter).contains("{\"term\":{\"tenantId\":\"HOSP-WEST\"}}");
        assertThat(filter).contains("{\"term\":{\"patientId\":\"PAT-9082341\"}}");
        assertThat(filter).doesNotContain("demographics.abhaId");
    }

    @Test
    void shouldOmitTenantFilterWhenTenantIsAll() {
        // Cross-tenant federated view (ABDM consent scenario)
        String filter = client.buildFilter("ALL", "PAT-9082341");

        assertThat(filter).doesNotContain("tenantId");
        assertThat(filter).contains("{\"term\":{\"patientId\":\"PAT-9082341\"}}");
    }

    @Test
    void shouldOmitTenantFilterWhenTenantIsNull() {
        String filter = client.buildFilter(null, "PAT-9082341");

        assertThat(filter).doesNotContain("tenantId");
        assertThat(filter).contains("{\"term\":{\"patientId\":\"PAT-9082341\"}}");
    }

    @Test
    void shouldBuildAbhaQueryWithBoolShouldMatchingRawAndMaskedTokens() {
        String abhaId = "14-8765-4321-9876";
        String filter = client.buildFilter("ALL", abhaId);

        // Omits tenantId for cross-hospital federated view
        assertThat(filter).doesNotContain("tenantId");

        // Checks raw ABHA ID
        assertThat(filter).contains(abhaId);
        // Checks deterministic PHI masked token [PHI:ABHA:<sha256-hex6>]
        String expectedHash = client.hashAbha(abhaId);
        assertThat(filter).contains("[PHI:ABHA:" + expectedHash + "]");
        // Checks minimum_should_match
        assertThat(filter).contains("\"minimum_should_match\": 1");
    }

    @Test
    void shouldCombineTenantFilterAndAbhaQueryWhenSingleTenantSpecified() {
        String abhaId = "14-8765-4321-9876";
        String filter = client.buildFilter("TMH-MUMBAI", abhaId);

        assertThat(filter).contains("{\"term\":{\"tenantId\":\"TMH-MUMBAI\"}}");
        assertThat(filter).contains("demographics.abhaId.keyword");
        assertThat(filter).contains("[PHI:ABHA:" + client.hashAbha(abhaId) + "]");
    }

    @Test
    void shouldProduceConsistentAbhaHash() {
        // Deterministic SHA-256 6-char substring
        String abhaId = "14-8765-4321-9876";
        String hash = client.hashAbha(abhaId);

        assertThat(hash).isNotNull();
        assertThat(hash).hasSize(6);
        assertThat(hash).isEqualTo("98527e");
    }
}
