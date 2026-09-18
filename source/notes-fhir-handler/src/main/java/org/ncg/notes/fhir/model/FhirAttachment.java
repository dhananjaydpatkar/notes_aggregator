package org.ncg.notes.fhir.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FhirAttachment(String contentType, String url, String title, String creation) {}
