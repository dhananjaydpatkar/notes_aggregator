package org.ncg.notes.phi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ncg.notes.common.model.Author;
import org.ncg.notes.common.model.ClinicalNote;
import org.ncg.notes.common.model.Demographics;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.model.NoteContent;

public class IndianAndHipaaPhiMaskerTest {

    private IndianAndHipaaPhiMasker masker;

    @BeforeEach
    public void setUp() {
        masker = new IndianAndHipaaPhiMasker();
    }

    @Test
    public void shouldMaskIndianAadhaarAndAbhaNumbers() {
        String text = "Patient Aadhaar: 5432 1234 5678 and ABHA number is 14-1234-5678-9012.";
        String masked = masker.maskText(text, null);

        assertThat(masked)
            .doesNotContain("5432 1234 5678")
            .doesNotContain("14-1234-5678-9012")
            .contains("[PHI:AADHAAR:")
            .contains("[PHI:ABHA:");
    }

    @Test
    public void shouldMaskIndianMobileAndEmailAndPan() {
        String text = "Contact patient at +91 9876543210 or email test.patient@example.com. PAN: ABCDE1234F.";
        String masked = masker.maskText(text, null);

        assertThat(masked)
            .doesNotContain("9876543210")
            .doesNotContain("test.patient@example.com")
            .doesNotContain("ABCDE1234F")
            .contains("[PHI:PHONE:")
            .contains("[PHI:EMAIL:")
            .contains("[PHI:PAN:");
    }

    @Test
    public void shouldMaskStructuredAuthorAndDemographics() {
        ClinicalNote note = ClinicalNote.builder()
            .noteId("NOTE-1")
            .tenantId("HOSP-WEST")
            .patientId("PAT-100")
            .author(Author.builder().clinicianId("DOC-77").name("Dr. Arvind Sharma").department("Medical Oncology").build())
            .demographics(Demographics.builder().age(45).gender("M").abhaId("14-9999-8888-7777").build())
            .content(NoteContent.builder()
                .title("Chemo Protocol")
                .sections(Map.of("assessment", "Colorectal carcinoma. Patient Aadhaar 9876 5432 1098."))
                .rawText("Chemo Protocol: Patient Aadhaar 9876 5432 1098.")
                .build())
            .build();

        MaskedNote maskedNote = masker.mask(note);

        // Clinician name is preserved unmasked
        assertThat(maskedNote.author().name()).isEqualTo("Dr. Arvind Sharma");
        assertThat(maskedNote.author().clinicianId()).isEqualTo("DOC-77");
        assertThat(maskedNote.author().department()).isEqualTo("Medical Oncology");

        assertThat(maskedNote.demographics().abhaId())
            .doesNotContain("14-9999-8888-7777")
            .contains("[PHI:ABHA:");
        assertThat(maskedNote.content().sections().get("assessment"))
            .doesNotContain("9876 5432 1098")
            .contains("[PHI:AADHAAR:");
        assertThat(maskedNote.summary()).contains("Colorectal carcinoma");
        assertThat(maskedNote.maskingMetadata().tokensMaskedCount()).isGreaterThanOrEqualTo(2);
    }
}
