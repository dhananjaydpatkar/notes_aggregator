package org.ncg.notes.fhir.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * FHIR R4 Bundle (searchset).
 * Spec: https://hl7.org/fhir/R4/bundle.html
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FhirBundle(
    String resourceType,   // always "Bundle"
    String id,
    String type,           // "searchset" | "transaction-response"
    int total,
    List<FhirLink> link,
    List<FhirEntry> entry
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirLink(String relation, String url) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirEntry(String fullUrl, FhirDocumentReference resource) {}
}
