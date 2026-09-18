package org.ncg.notes.fhir.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * FHIR R4 DocumentReference resource.
 * Spec: https://hl7.org/fhir/R4/documentreference.html
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FhirDocumentReference(
    String resourceType,        // always "DocumentReference"
    String id,                  // noteId
    List<FhirIdentifier> identifier,
    String status,              // "current" | "superseded" | "entered-in-error"
    FhirCodeableConcept type,   // LOINC code for note type
    List<FhirReference> category,
    FhirReference subject,      // Patient reference
    String date,                // authoredAt ISO-8601
    List<FhirReference> author, // Practitioner references
    String description,         // 1-line summary
    List<FhirContent> content,  // attachment URLs
    FhirContext context,        // encounter, period, facility
    List<FhirExtension> extension // Clinical extensions: tenantId, sourceSystem
) {
    /** DocumentReference.context inner structure */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirContext(
        List<FhirReference> encounter,
        FhirReference sourcePatientInfo
    ) {}
}
