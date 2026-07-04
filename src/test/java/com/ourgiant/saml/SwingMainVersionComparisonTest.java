package com.ourgiant.saml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingMainVersionComparisonTest {

    @ParameterizedTest
    @CsvSource({
            "1.0.10, 1.0.9",
            "1.1.0, 1.0.9",
            "2.0.0, 1.9.9",
            "1.0.10, 1.0.2",
            "1.0.0.1, 1.0.0"
    })
    void isNewerVersion_returnsTrueWhenLatestIsGreater(String latest, String current) {
        assertTrue(SwingMain.isNewerVersion(latest, current));
    }

    @ParameterizedTest
    @CsvSource({
            "1.0.9, 1.0.9",
            "1.0.9, 1.0.10",
            "1.0.9, 1.1.0",
            "1.9.9, 2.0.0",
            "1.0.0, 1.0.0.1"
    })
    void isNewerVersion_returnsFalseWhenLatestIsNotGreater(String latest, String current) {
        assertFalse(SwingMain.isNewerVersion(latest, current));
    }

    @ParameterizedTest
    @CsvSource({
            "not-a-version, 1.0.9",
            "1.0.9, not-a-version"
    })
    void isNewerVersion_returnsFalseOnUnparsableInput(String latest, String current) {
        assertFalse(SwingMain.isNewerVersion(latest, current));
    }

    @Test
    void extractJsonString_findsValueForKey() {
        String json = "{\"tag_name\":\"v1.0.10\",\"html_url\":\"https://example.com/releases/v1.0.10\"}";
        assertEquals("v1.0.10", SwingMain.extractJsonString(json, "tag_name"));
        assertEquals("https://example.com/releases/v1.0.10", SwingMain.extractJsonString(json, "html_url"));
    }

    @Test
    void extractJsonString_returnsNullWhenKeyMissing() {
        assertNull(SwingMain.extractJsonString("{\"other_key\":\"value\"}", "tag_name"));
    }
}
