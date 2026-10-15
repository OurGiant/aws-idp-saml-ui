package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayMenuEntriesTest {

    private static final Set<String> KNOWN = Set.of("dev", "prod", "old", "new");

    @Test
    void build_keepsPinOrderAndLabelsStatus() {
        TrayMenuEntries entries = TrayMenuEntries.build(List.of("prod", "dev", "old", "new"),
            Map.of("prod", "VALID", "dev", "VALID", "old", "EXPIRED"), Set.of("dev"),
            Set.of("prod", "dev", "old"), KNOWN, false);

        assertEquals(List.of("prod — valid", "dev — expiring soon", "old — expired", "new — no credentials"),
            entries.profiles().stream().map(TrayMenuEntries.Entry::label).toList());
    }

    @Test
    void build_openConsoleNeedsCredentialsAndRequestNeedsNoRequestRunning() {
        TrayMenuEntries idle = TrayMenuEntries.build(List.of("dev", "new"), Map.of("dev", "VALID"), Set.of(),
            Set.of("dev"), KNOWN, false);
        assertTrue(idle.profiles().get(0).canOpenConsole());
        assertFalse(idle.profiles().get(1).canOpenConsole());
        assertTrue(idle.profiles().get(1).canRequest());

        TrayMenuEntries busy = TrayMenuEntries.build(List.of("dev"), Map.of("dev", "VALID"), Set.of(),
            Set.of("dev"), KNOWN, true);
        assertFalse(busy.profiles().get(0).canRequest());
        assertTrue(busy.profiles().get(0).canOpenConsole());
        assertTrue(busy.requestInProgress());
    }

    @Test
    void build_skipsPinnedProfilesThatNoLongerExist() {
        TrayMenuEntries entries = TrayMenuEntries.build(List.of("gone", "dev"), Map.of(), Set.of(), Set.of(), KNOWN, false);

        assertEquals(List.of("dev"), entries.profiles().stream().map(TrayMenuEntries.Entry::profile).toList());
    }

    @Test
    void equality_detectsOnlyRealChanges() {
        TrayMenuEntries a = TrayMenuEntries.build(List.of("dev"), Map.of("dev", "VALID"), Set.of(), Set.of("dev"), KNOWN, false);
        TrayMenuEntries same = TrayMenuEntries.build(List.of("dev"), Map.of("dev", "VALID"), Set.of(), Set.of("dev"), KNOWN, false);
        TrayMenuEntries expired = TrayMenuEntries.build(List.of("dev"), Map.of("dev", "EXPIRED"), Set.of(), Set.of("dev"), KNOWN, false);

        assertEquals(a, same);
        assertNotEquals(a, expired);
    }
}
