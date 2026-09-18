package org.ncg.notes.fhir.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FhirContent(FhirAttachment attachment) {}
