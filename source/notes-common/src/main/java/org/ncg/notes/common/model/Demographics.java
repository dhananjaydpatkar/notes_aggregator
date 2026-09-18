package org.ncg.notes.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Demographics(
    Integer age,
    String gender,
    String abhaId,
    String aadhaarLast4,
    String city,
    String state,
    String pincode
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Integer age;
        private String gender;
        private String abhaId;
        private String aadhaarLast4;
        private String city;
        private String state;
        private String pincode;

        public Builder age(Integer age) {
            this.age = age;
            return this;
        }

        public Builder gender(String gender) {
            this.gender = gender;
            return this;
        }

        public Builder abhaId(String abhaId) {
            this.abhaId = abhaId;
            return this;
        }

        public Builder aadhaarLast4(String aadhaarLast4) {
            this.aadhaarLast4 = aadhaarLast4;
            return this;
        }

        public Builder city(String city) {
            this.city = city;
            return this;
        }

        public Builder state(String state) {
            this.state = state;
            return this;
        }

        public Builder pincode(String pincode) {
            this.pincode = pincode;
            return this;
        }

        public Demographics build() {
            return new Demographics(age, gender, abhaId, aadhaarLast4, city, state, pincode);
        }
    }
}
