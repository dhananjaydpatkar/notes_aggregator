package org.ncg.notes.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record NoteContent(
    String format,
    String title,
    Map<String, String> sections,
    String rawText
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String format = "text/plain";
        private String title;
        private Map<String, String> sections = Collections.emptyMap();
        private String rawText;

        public Builder format(String format) {
            this.format = format;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder sections(Map<String, String> sections) {
            this.sections = sections != null ? sections : Collections.emptyMap();
            return this;
        }

        public Builder rawText(String rawText) {
            this.rawText = rawText;
            return this;
        }

        public NoteContent build() {
            return new NoteContent(format, title, sections, rawText);
        }
    }
}
