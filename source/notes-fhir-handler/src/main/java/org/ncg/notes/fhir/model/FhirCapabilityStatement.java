package org.ncg.notes.fhir.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * FHIR R4 CapabilityStatement — returned at /fhir/r4/metadata.
 * Spec: https://hl7.org/fhir/R4/capabilitystatement.html
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FhirCapabilityStatement(
    String resourceType,
    String id,
    String status,
    String date,
    String kind,
    String fhirVersion,
    List<String> format,
    Software software,
    List<Rest> rest
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Software(String name, String version) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rest(String mode, List<Resource> resource) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resource(
        String type,
        List<Interaction> interaction,
        List<SearchParam> searchParam
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Interaction(String code, String documentation) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchParam(String name, String type, String documentation) {}

    public static FhirCapabilityStatement defaultCapabilityStatement(String date) {
        return new FhirCapabilityStatement(
            "CapabilityStatement",
            "clinical-notes-aggregator",
            "active",
            date,
            "instance",
            "4.0.1",
            List.of("application/fhir+json", "application/json"),
            new Software("Clinical Notes Aggregator", "1.0.0"),
            List.of(new Rest("server", List.of(
                new Resource(
                    "DocumentReference",
                    List.of(
                        new Interaction("search-type", "Search notes by patient, type, date"),
                        new Interaction("read", "Retrieve single note by id"),
                        new Interaction("create", "Ingest a note as FHIR DocumentReference")
                    ),
                    List.of(
                        new SearchParam("patient", "reference", "Patient ID (PAT-xxx) or ABHA URN"),
                        new SearchParam("patient.identifier", "token", "ABHA identifier: https://abdm.gov.in/abha|<abha-number>"),
                        new SearchParam("type", "token", "LOINC code, e.g. http://loinc.org|11506-3"),
                        new SearchParam("date", "date", "Filter by authored date, supports ge/le/gt/lt prefix"),
                        new SearchParam("_count", "number", "Page size (default 20, max 100)"),
                        new SearchParam("_offset", "number", "Pagination offset"),
                        new SearchParam("_sort", "string", "Sort field: -date (default, newest first)")
                    )
                )
            )))
        );
    }
}
