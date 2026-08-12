package com.ourgiant.saml.gui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AboutDialogVersionComparisonTest {

    @ParameterizedTest
    @CsvSource({
            "1.0.10, 1.0.9",
            "1.1.0, 1.0.9",
            "2.0.0, 1.9.9",
            "1.0.10, 1.0.2",
            "1.0.0.1, 1.0.0"
    })
    void isNewerVersion_returnsTrueWhenLatestIsGreater(String latest, String current) {
        assertTrue(AboutDialog.isNewerVersion(latest, current));
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
        assertFalse(AboutDialog.isNewerVersion(latest, current));
    }

    @ParameterizedTest
    @CsvSource({
            "not-a-version, 1.0.9",
            "1.0.9, not-a-version"
    })
    void isNewerVersion_returnsFalseOnUnparsableInput(String latest, String current) {
        assertFalse(AboutDialog.isNewerVersion(latest, current));
    }
}
