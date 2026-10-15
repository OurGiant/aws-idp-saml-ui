package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatsNewTest {

    private static final List<String> BUNDLED = List.of("1.7.0", "1.8.0", "1.7.2", "2.0.0");

    @Test
    void versionsToShow_upgradeShowsEverythingMissedUpToCurrent_newestFirst() {
        assertEquals(List.of("1.8.0", "1.7.2", "1.7.0"), WhatsNew.versionsToShow(BUNDLED, "1.6.5", "1.8.0"));
        assertEquals(List.of("1.8.0"), WhatsNew.versionsToShow(BUNDLED, "1.7.2", "1.8.3"));
    }

    @Test
    void versionsToShow_noLastSeenMeansPreWhatsNewInstall_showsAllUpToCurrent() {
        assertEquals(List.of("1.7.0"), WhatsNew.versionsToShow(BUNDLED, null, "1.7.0"));
    }

    @Test
    void versionsToShow_nothingWhenAlreadySeenOrNotReleasedOrDevBuild() {
        assertTrue(WhatsNew.versionsToShow(BUNDLED, "1.7.0", "1.7.0").isEmpty());
        assertTrue(WhatsNew.versionsToShow(BUNDLED, "1.6.5", "1.6.16").isEmpty(), "1.7.0 notes wait for 1.7.0");
        assertTrue(WhatsNew.versionsToShow(BUNDLED, null, "unknown").isEmpty());
        assertTrue(WhatsNew.versionsToShow(BUNDLED, null, null).isEmpty());
    }

    @Test
    void latestNotesFor_picksNewestNotesNoNewerThanCurrent() {
        assertEquals("1.7.2", WhatsNew.latestNotesFor(BUNDLED, "1.7.5"));
        assertNull(WhatsNew.latestNotesFor(BUNDLED, "1.6.9"));
    }

    @Test
    void bundledResources_indexListsReadableNotesFor170() {
        List<String> bundled = WhatsNew.bundledVersions();
        assertTrue(bundled.contains("1.7.0"), bundled.toString());
        for (String version : bundled) {
            String html = WhatsNew.notesHtml(List.of(version));
            assertTrue(html != null && html.contains("Version " + version), "notes for " + version + " missing or untitled");
        }
        assertNull(WhatsNew.notesHtml(List.of("0.0.1")));
    }

    @Test
    void notesHtml_joinsSeveralVersionsWithRule() {
        String html = WhatsNew.notesHtml(List.of("1.7.0", "1.7.0"));
        assertTrue(html.contains("<hr>"), html);
    }
}
