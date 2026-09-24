package gov.ttb.labelverification.ai.compare;

import static org.assertj.core.api.Assertions.assertThat;

import gov.ttb.labelverification.regulatory.HealthWarning;
import org.junit.jupiter.api.Test;

class OcrTextSearchTest {

    @Test
    void exactSubstringReturnsOcrSpan() {
        assertThat(OcrTextSearch.find("xx\nALDER CREST\nBourbon", "Alder Crest")).isEqualTo("ALDER CREST");
    }

    @Test
    void punctuationDropsAreTolerated() {
        assertThat(OcrTextSearch.find("STONES THROW distillery", "Stone's Throw")).isEqualTo("Stone's Throw");
    }

    @Test
    void garbledHealthWarningIsRecognisedByLandmarkAndBodyPhrases() {
        String ocr = """
                GOVERMENT WARNlNG: (1) Acc0rding to the surgeon general, women should not drink
                alcoholic beverages during pregnancy because of the risk of birth defects. (2) Consumptlon
                of alcoholic beverages impairs your ability to drive a car or operate machinery,
                and may cause health problems.""";
        assertThat(OcrTextSearch.find(ocr, HealthWarning.FULL_TEXT)).isEqualTo(HealthWarning.FULL_TEXT);
    }

    @Test
    void prefixAloneWithIllegibleBodyIsNotAMatch() {
        String ocr = "GOVERNMENT WARNING: ~~ %% ## unreadable small print ## %%";
        assertThat(OcrTextSearch.find(ocr, HealthWarning.FULL_TEXT)).isNull();
    }

    @Test
    void scatteredWordsOnDecorativeLabelsMatch() {
        String ocr = "ALDER\n~ ~ ~\nSMALL BATCH\nCREST\n6 YEARS";
        assertThat(OcrTextSearch.find(ocr, "Alder Crest")).isEqualTo("Alder Crest");
    }

    @Test
    void absentTextReturnsNull() {
        assertThat(OcrTextSearch.find("Tidewater Row Lager", "Alder Crest")).isNull();
    }

    @Test
    void numericFieldsKeepTheValueActuallyOnTheLabel() {
        String ocr = "NORTHVALE Vodka 40% Alc./Vol. (80 Proof) 750 mL";
        // Text search alone would accept the near-identical string as "present"…
        assertThat(OcrTextSearch.find(ocr, "42% Alc./Vol.")).isEqualTo("42% Alc./Vol.");
        // …so numeric fields return what the label really says, for the comparator to reject.
        String found = OcrTextSearch.find(ocr, "42% Alc./Vol.", true);
        assertThat(found).contains("40%");
        assertThat(FieldComparator.compare(gov.ttb.labelverification.regulatory.FieldName.ALCOHOL_CONTENT,
                "42% Alc./Vol.", found).status()).isEqualTo(gov.ttb.labelverification.domain.ItemStatus.MISMATCH);
    }
}
