package org.ncg.notes.common.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Timestamps(
    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    Instant authoredAt,

    @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
    Instant recordedAt
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Instant authoredAt;
        private Instant recordedAt;

        public Builder authoredAt(Instant authoredAt) {
            this.authoredAt = authoredAt;
            return this;
        }

        public Builder recordedAt(Instant recordedAt) {
            this.recordedAt = recordedAt;
            return this;
        }

        public Timestamps build() {
            return new Timestamps(
                authoredAt != null ? authoredAt : Instant.now(),
                recordedAt != null ? recordedAt : Instant.now()
            );
        }
    }
}
