package org.ncg.notes.common.port;

import org.ncg.notes.common.model.ClinicalNote;
import org.ncg.notes.common.model.MaskedNote;

/**
 * Port interface for PHI/PII masking and de-identification.
 * Pluggable implementation: Indian & HIPAA Regex NER, Phileas, or AWS Comprehend Medical.
 */
public interface PhiMaskingPort {
    MaskedNote mask(ClinicalNote note);
}
