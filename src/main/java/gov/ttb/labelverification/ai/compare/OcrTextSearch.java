package gov.ttb.labelverification.ai.compare;

import static gov.ttb.labelverification.ai.compare.TextNormalizer.fuzzyMatch;
import static gov.ttb.labelverification.ai.compare.TextNormalizer.normalizeWhitespace;

import gov.ttb.labelverification.regulatory.HealthWarning;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Finds an expected value inside noisy OCR text. Used by the local pipeline,
 * which has no LLM to classify text into fields.
 * <p>
 * Strategy, in order:
 * <ol>
 *   <li>Exact case-insensitive substring</li>
 *   <li>Punctuation-insensitive substring (OCR drops dots, commas, hyphens)</li>
 *   <li>Landmark prefix ("GOVERNMENT WARNING") + ≥4 of 6 key body phrases</li>
 *   <li>Sliding word window with bigram similarity</li>
 *   <li>Scattered word matching (every significant word present somewhere)</li>
 * </ol>
 * A strong fuzzy hit (≥ 0.75) means the text is on the label and the
 * difference is OCR noise, so the expected value is returned verbatim —
 * except for numeric fields, where the OCR text is returned so the
 * comparator can catch a changed digit.
 */
public final class OcrTextSearch {

    public static final double MIN_SIMILARITY = 0.6;
    public static final double HIGH_CONFIDENCE = 0.75;

    private static final List<String> LANDMARK_PREFIXES = List.of(HealthWarning.PREFIX.toLowerCase(Locale.ROOT));

    private static final List<String> HEALTH_WARNING_BODY_PHRASES = List.of(
            "surgeon general", "pregnancy", "birth defects", "drive a car", "operate machinery", "health problems");

    private OcrTextSearch() {
    }

    public static String find(String ocrText, String expectedValue) {
        return find(ocrText, expectedValue, false);
    }

    /**
     * @param preserveOcrValue true for values whose small differences are meaningful
     *                         (alcohol content, net contents, age, vintage). Fuzzy hits then
     *                         return the text actually on the label instead of the expected
     *                         value, so "40%" on the label is never reported as "42%".
     */
    public static String find(String ocrText, String expectedValue, boolean preserveOcrValue) {
        String normalizedOcr = normalizeWhitespace(ocrText);
        String normalizedExpected = normalizeWhitespace(expectedValue);
        if (normalizedOcr.isEmpty() || normalizedExpected.isEmpty()) {
            return null;
        }
        String lowerOcr = normalizedOcr.toLowerCase(Locale.ROOT);
        String lowerExpected = normalizedExpected.toLowerCase(Locale.ROOT);

        // Stage 1: exact substring
        int idx = lowerOcr.indexOf(lowerExpected);
        if (idx >= 0) {
            return normalizedOcr.substring(idx, idx + normalizedExpected.length());
        }

        // Stage 2: punctuation-normalized substring
        if (stripPunctuation(lowerOcr).contains(stripPunctuation(lowerExpected))) {
            return expectedValue;
        }

        // Stage 3: landmark prefix with legible body
        for (String prefix : LANDMARK_PREFIXES) {
            if (lowerExpected.startsWith(prefix) && landmarkPresent(lowerOcr, prefix)) {
                return expectedValue;
            }
        }

        // Stage 4: sliding window
        String[] ocrWords = normalizedOcr.split("\\s+");
        int expectedWordCount = normalizedExpected.split("\\s+").length;
        double bestScore = 0;
        String bestMatch = null;
        int minWords = Math.max(1, expectedWordCount - 2);
        int maxWords = Math.min(ocrWords.length, expectedWordCount + 3);
        for (int ws = minWords; ws <= maxWords; ws++) {
            for (int i = 0; i <= ocrWords.length - ws; i++) {
                String candidate = String.join(" ", Arrays.copyOfRange(ocrWords, i, i + ws));
                double sim = fuzzyMatch(candidate, normalizedExpected).similarity();
                if (sim > bestScore) {
                    bestScore = sim;
                    bestMatch = candidate;
                }
            }
        }
        if (bestScore >= HIGH_CONFIDENCE) {
            return preserveOcrValue ? bestMatch : expectedValue;
        }

        // Stage 5: scattered words (decorative labels put each word on its own line)
        List<String> expectedWords = Arrays.stream(normalizedExpected.split("\\s+"))
                .filter(w -> w.length() >= 3).toList();
        if (expectedWords.size() >= 2 && !preserveOcrValue) {
            String[] ocrLower = lowerOcr.split("\\s+");
            boolean allFound = expectedWords.stream()
                    .map(w -> w.toLowerCase(Locale.ROOT))
                    .allMatch(ew -> Arrays.stream(ocrLower).anyMatch(ow -> ow.equals(ew)
                            || (Math.abs(ow.length() - ew.length()) <= 3
                            && fuzzyMatch(ow, ew).similarity() >= HIGH_CONFIDENCE)));
            if (allFound) {
                return expectedValue;
            }
        }

        return bestScore >= MIN_SIMILARITY ? bestMatch : null;
    }

    private static boolean landmarkPresent(String lowerOcr, String prefix) {
        String[] prefixWords = prefix.replaceAll("[.:,]", "").split("\\s+");
        String[] ocrWords = lowerOcr.replaceAll("[.:,]", "").split("\\s+");
        String target = String.join(" ", prefixWords);

        boolean prefixFound = false;
        for (int i = 0; i <= ocrWords.length - prefixWords.length; i++) {
            String candidate = String.join(" ", Arrays.copyOfRange(ocrWords, i, i + prefixWords.length));
            if (fuzzyMatch(candidate, target).similarity() >= HIGH_CONFIDENCE) {
                prefixFound = true;
                break;
            }
        }
        if (!prefixFound) {
            return false;
        }

        // The bold prefix often survives OCR while the small-print body does not.
        // Require at least 4 of 6 key body phrases before trusting the match.
        String ocrClean = lowerOcr.replaceAll("[.:,]", "");
        String[] ocrW = ocrClean.split("\\s+");
        int phrasesFound = 0;
        for (String phrase : HEALTH_WARNING_BODY_PHRASES) {
            if (ocrClean.contains(phrase)) {
                phrasesFound++;
                continue;
            }
            boolean allWords = Arrays.stream(phrase.split("\\s+")).allMatch(pw -> Arrays.stream(ocrW)
                    .anyMatch(ow -> Math.abs(ow.length() - pw.length()) <= 2
                            && fuzzyMatch(ow, pw).similarity() >= HIGH_CONFIDENCE));
            if (allWords) {
                phrasesFound++;
            }
        }
        return phrasesFound >= 4;
    }

    private static String stripPunctuation(String s) {
        return s.replaceAll("[.,'\\-]", "");
    }
}
