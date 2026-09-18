package org.ncg.notes.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CodingEntry(
    String system,
    String code,
    String display
) {
    public static CodingEntry of(String system, String code, String display) {
        return new CodingEntry(system, code, display);
    }
}
