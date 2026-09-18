package org.ncg.notes.fhir.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FhirReference(String reference, String display, List<FhirIdentifier> identifier) {}
