package org.ncg.notes.fhir.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FhirCodeableConcept(List<FhirCoding> coding, String text) {}
