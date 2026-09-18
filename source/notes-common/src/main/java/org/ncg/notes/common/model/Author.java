package org.ncg.notes.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Author(
    String clinicianId,
    String name,
    String department
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String clinicianId;
        private String name;
        private String department;

        public Builder clinicianId(String clinicianId) {
            this.clinicianId = clinicianId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder department(String department) {
            this.department = department;
            return this;
        }

        public Author build() {
            return new Author(clinicianId, name, department);
        }
    }
}
