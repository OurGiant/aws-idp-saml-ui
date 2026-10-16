package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuggestCopyNameTest {

    @Test
    void suggestsCopyThenNumberedCopies() {
        assertEquals("dev-copy", ProfileManagerDialog.suggestCopyName("dev", Set.of("dev")));
        assertEquals("dev-copy-2", ProfileManagerDialog.suggestCopyName("dev", Set.of("dev", "dev-copy")));
        assertEquals("dev-copy-3", ProfileManagerDialog.suggestCopyName("dev", Set.of("dev", "dev-copy", "dev-copy-2")));
    }
}
