package org.ncg.notes.fhir.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * FHIR R4 OperationOutcome — returned for errors.
 * Spec: https://hl7.org/fhir/R4/operationoutcome.html
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FhirOperationOutcome(
    String resourceType,
    List<Issue> issue
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Issue(
        String severity,     // "error" | "warning" | "information"
        String code,         // "not-found" | "invalid" | "processing" etc.
        FhirCodeableConcept details,
        String diagnostics
    ) {}

    public static FhirOperationOutcome error(String code, String message) {
        return new FhirOperationOutcome("OperationOutcome", List.of(
            new Issue("error", code,
                new FhirCodeableConcept(null, message),
                message)
        ));
    }

    public static FhirOperationOutcome notFound(String id) {
        return error("not-found", "Resource not found: " + id);
    }
}
