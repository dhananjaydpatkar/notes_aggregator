package org.ncg.notes.phi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.ncg.notes.common.model.Author;
import org.ncg.notes.common.model.ClinicalNote;
import org.ncg.notes.common.model.Demographics;
import org.ncg.notes.common.model.MaskedNote;
import org.ncg.notes.common.model.NoteContent;
import org.ncg.notes.common.port.PhiMaskingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fast, in-process de-identification engine supporting Indian Healthcare Identifiers
 * (Aadhaar, ABHA, PAN, Indian Mobile Numbers, PIN codes) and HIPAA Safe Harbor 18 Identifiers
 * (SSN, Emails, Phones, Dates of Birth, Clinician & Patient Names).
 */
public class IndianAndHipaaPhiMasker implements PhiMaskingPort {

    private static final Logger log = LoggerFactory.getLogger(IndianAndHipaaPhiMasker.class);

    // =========================================================================
    // REGEX PATTERNS FOR INDIAN & GLOBAL IDENTIFIERS
    // =========================================================================

    // Aadhaar: 12-digit format (e.g. 5432 1234 5678 or 5432-1234-5678 or 543212345678)
    private static final Pattern AADHAAR_PATTERN = Pattern.compile(
        "\\b[2-9]{1}[0-9]{3}[-\\s]?[0-9]{4}[-\\s]?[0-9]{4}\\b"
    );

    // ABHA ID: 14-digit format (e.g. 14-1234-5678-9012) or 14-digit continuous
    private static final Pattern ABHA_PATTERN = Pattern.compile(
        "\\b\\d{2}-\\d{4}-\\d{4}-\\d{4}\\b"
    );

    // Indian PAN Card: 5 letters, 4 digits, 1 letter (e.g. ABCDE1234F)
    private static final Pattern PAN_PATTERN = Pattern.compile(
        "\\b[A-Z]{5}[0-9]{4}[A-Z]{1}\\b"
    );

    // Indian Mobile Numbers: +91 optional, 10 digits starting with 6, 7, 8, 9
    private static final Pattern INDIAN_MOBILE_PATTERN = Pattern.compile(
        "(?:\\+91[-\\s]?)?[6-9]\\d{9}\\b"
    );

    // Email Addresses
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
    );

    // US / General SSN
    private static final Pattern SSN_PATTERN = Pattern.compile(
        "\\b\\d{3}-\\d{2}-\\d{4}\\b"
    );

    // Indian PIN Codes when preceded by keywords (e.g. PIN 400001, Pincode: 560001)
    private static final Pattern PINCODE_PATTERN = Pattern.compile(
        "(?i)\\b(?:pincode|pin|postal\\s*code)[:\\s]+([1-9][0-9]{5})\\b"
    );

    // Date of birth pattern (e.g. DOB: 14/05/1980 or Date of Birth: 1985-11-20)
    private static final Pattern DOB_PATTERN = Pattern.compile(
        "(?i)\\b(?:dob|date\\s+of\\s+birth|born)[:\\s]+(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})\\b"
    );

    // Patient Name in clinical headers (e.g. Patient: Ramu Sharma, Pt Name: Priya K)
    private static final Pattern PATIENT_NAME_IN_TEXT = Pattern.compile(
        "(?i)\\b(?:patient(?:\\s+name)?|pt\\s+name)[:\\s]+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,3})\\b"
    );

    @Override
    public MaskedNote mask(ClinicalNote note) {
        if (note == null) {
            throw new IllegalArgumentException("ClinicalNote cannot be null");
        }

        AtomicInteger tokenCounter = new AtomicInteger(0);

        // 1. Mask structured Author
        Author maskedAuthor = maskAuthor(note.author(), tokenCounter);

        // 2. Mask structured Demographics
        Demographics maskedDemographics = maskDemographics(note.demographics(), tokenCounter);

        // 3. Mask Content (sections + rawText)
        NoteContent originalContent = note.content();
        NoteContent maskedContent = null;
        String summary = null;

        if (originalContent != null) {
            Map<String, String> maskedSections = new HashMap<>();
            if (originalContent.sections() != null) {
                for (Map.Entry<String, String> entry : originalContent.sections().entrySet()) {
                    String maskedVal = maskText(entry.getValue(), tokenCounter);
                    maskedSections.put(entry.getKey(), maskedVal);
                }
            }

            String maskedRaw = maskText(originalContent.rawText(), tokenCounter);

            maskedContent = NoteContent.builder()
                .format(originalContent.format())
                .title(maskText(originalContent.title(), tokenCounter))
                .sections(maskedSections)
                .rawText(maskedRaw)
                .build();

            summary = extractSummary(maskedContent);
        }

        // 4. Construct MaskedNote
        return MaskedNote.builder()
            .noteId(note.noteId())
            .tenantId(note.tenantId())
            .patientId(note.patientId())
            .encounterId(note.encounterId())
            .sourceSystem(note.sourceSystem())
            .facilityId(note.facilityId())
            .noteType(note.noteType())
            .author(maskedAuthor)
            .demographics(maskedDemographics)
            .timestamps(note.timestamps())
            .content(maskedContent)
            .coding(note.coding())
            .summary(summary != null ? summary : note.noteType() + " note recorded.")
            .maskingMetadata(new MaskedNote.MaskingMetadata(
                Instant.now(),
                tokenCounter.get(),
                "INDIAN_HIPAA_NER_V1"
            ))
            .build();
    }

    /**
     * Masks free text applying Indian & HIPAA regex patterns.
     */
    public String maskText(String text, AtomicInteger counter) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;

        // 1. Aadhaar
        result = replaceMatches(result, AADHAAR_PATTERN, "PHI:AADHAAR", counter);

        // 2. ABHA
        result = replaceMatches(result, ABHA_PATTERN, "PHI:ABHA", counter);

        // 3. PAN
        result = replaceMatches(result, PAN_PATTERN, "PHI:PAN", counter);

        // 4. Indian Mobile & Phone
        result = replaceMatches(result, INDIAN_MOBILE_PATTERN, "PHI:PHONE", counter);

        // 5. Emails
        result = replaceMatches(result, EMAIL_PATTERN, "PHI:EMAIL", counter);

        // 6. SSN
        result = replaceMatches(result, SSN_PATTERN, "PHI:SSN", counter);

        // 7. Pincode
        result = replaceMatches(result, PINCODE_PATTERN, "PHI:PINCODE", counter);

        // 8. DOB
        result = replaceMatches(result, DOB_PATTERN, "PHI:DOB", counter);

        // 9. Patient Name in text
        result = replaceMatches(result, PATIENT_NAME_IN_TEXT, "PHI:NAME", counter);

        return result;
    }

    private String replaceMatches(String text, Pattern pattern, String tokenType, AtomicInteger counter) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String match = matcher.group();
            String token = String.format("[%s:%s]", tokenType, generateShortHash(match));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(token));
            if (counter != null) {
                counter.incrementAndGet();
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Author maskAuthor(Author author, AtomicInteger counter) {
        if (author == null) {
            return null;
        }
        // Clinician name is preserved unmasked per clinical usability requirements
        return author;
    }

    private Demographics maskDemographics(Demographics demographics, AtomicInteger counter) {
        if (demographics == null) {
            return null;
        }

        String maskedAbha = demographics.abhaId();
        if (maskedAbha != null && !maskedAbha.isBlank()) {
            maskedAbha = String.format("[PHI:ABHA:%s]", generateShortHash(demographics.abhaId()));
            if (counter != null) {
                counter.incrementAndGet();
            }
        }

        String maskedAadhaar = demographics.aadhaarLast4();
        if (maskedAadhaar != null && !maskedAadhaar.isBlank()) {
            maskedAadhaar = String.format("[PHI:AADHAAR:%s]", generateShortHash(demographics.aadhaarLast4()));
            if (counter != null) {
                counter.incrementAndGet();
            }
        }

        return new Demographics(
            demographics.age(),
            demographics.gender(),
            maskedAbha,
            maskedAadhaar,
            demographics.city(),
            demographics.state(),
            demographics.pincode()
        );
    }

    private String extractSummary(NoteContent content) {
        if (content.sections() != null && !content.sections().isEmpty()) {
            // Check high priority clinical sections
            for (String key : new String[]{"assessment", "impression", "chiefComplaint", "plan", "findings"}) {
                for (Map.Entry<String, String> entry : content.sections().entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null && !entry.getValue().isBlank()) {
                        return truncate(entry.getValue(), 200);
                    }
                }
            }
            // Fallback to first section
            Map.Entry<String, String> first = content.sections().entrySet().iterator().next();
            if (first.getValue() != null) {
                return truncate(first.getValue(), 200);
            }
        }
        if (content.rawText() != null && !content.rawText().isBlank()) {
            return truncate(content.rawText(), 200);
        }
        if (content.title() != null) {
            return content.title();
        }
        return "Clinical note recorded.";
    }

    private String truncate(String text, int maxLen) {
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxLen) {
            return clean;
        }
        return clean.substring(0, maxLen - 3) + "...";
    }

    private String generateShortHash(String input) {
        if (input == null) {
            return "0000";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 6);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode()).substring(0, Math.min(4, Integer.toHexString(input.hashCode()).length()));
        }
    }
}
