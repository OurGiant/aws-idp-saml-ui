package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the "Profiles Imported" popup message formatting (one profile per line, capped with a summary). */
class ImportRolesDialogTest {

    private static List<String> profiles(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            names.add("profile-" + i);
        }
        return names;
    }

    @Test
    void singleProfileUsesSingularHeaderAndNoEllipsis() {
        String message = ImportRolesDialog.buildImportedMessage(List.of("only-one"));

        assertEquals("Imported 1 profile:\nonly-one", message);
        assertFalse(message.contains("more"), "a single profile should not add a summary line");
    }

    @Test
    void multipleProfilesUsePluralHeaderAndOnePerLine() {
        List<String> names = profiles(3);

        String message = ImportRolesDialog.buildImportedMessage(names);

        String[] lines = message.split("\n");
        assertEquals("Imported 3 profiles:", lines[0]);
        assertEquals(4, lines.length, "header line plus one line per profile");
        assertEquals("profile-1", lines[1]);
        assertEquals("profile-2", lines[2]);
        assertEquals("profile-3", lines[3]);
    }

    @Test
    void exactlyFifteenProfilesAreAllListedWithoutEllipsis() {
        List<String> names = profiles(15);

        String message = ImportRolesDialog.buildImportedMessage(names);

        String[] lines = message.split("\n");
        assertEquals("Imported 15 profiles:", lines[0]);
        assertEquals(16, lines.length, "header line plus all 15 profiles");
        assertEquals("profile-15", lines[15]);
        assertFalse(message.contains("more"), "exactly the cap should not be truncated");
    }

    @Test
    void sixteenProfilesListFirstFifteenThenSummariseOneMore() {
        List<String> names = profiles(16);

        String message = ImportRolesDialog.buildImportedMessage(names);

        String[] lines = message.split("\n");
        assertEquals("Imported 16 profiles:", lines[0]);
        assertEquals(17, lines.length, "header, 15 listed profiles, and the summary line");
        assertEquals("profile-15", lines[15]);
        assertEquals("…and 1 more", lines[16]);
        assertFalse(message.contains("profile-16"), "the 16th profile should be summarised, not listed");
    }

    @Test
    void manyProfilesSummariseTheCorrectRemainingCount() {
        List<String> names = profiles(42);

        String message = ImportRolesDialog.buildImportedMessage(names);

        String[] lines = message.split("\n");
        assertEquals("Imported 42 profiles:", lines[0]);
        assertEquals(17, lines.length, "header, 15 listed profiles, and the summary line");
        assertEquals("…and 27 more", lines[16]);
        assertTrue(message.startsWith("Imported 42 profiles:\nprofile-1\n"));
    }
}
