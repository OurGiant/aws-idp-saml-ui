package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageTipsTest {

    @Test
    void parse_splitsOnDashesAndSkipsCommentsAndIncompleteBlocks() {
        List<UsageTips.Tip> tips = UsageTips.parse("""
            # header comment
            ---
            First title
            First body line one
            line two
            ---
            # a comment inside
            Second title
            Second <b>body</b>
            ---
            Title with no body
            ---
            """);

        assertEquals(List.of(
            new UsageTips.Tip("First title", "First body line one line two"),
            new UsageTips.Tip("Second title", "Second <b>body</b>")), tips);
    }

    @Test
    void startIndex_wrapsStoredIndexIntoRange() {
        assertEquals(0, UsageTips.startIndex(0, 5));
        assertEquals(2, UsageTips.startIndex(7, 5));
        assertEquals(4, UsageTips.startIndex(-1, 5));
        assertEquals(0, UsageTips.startIndex(3, 0));
    }

    @Test
    void bundledTips_loadAndAreWellFormed() {
        List<UsageTips.Tip> tips = UsageTips.load();

        assertTrue(tips.size() >= 5, "expected the bundled tips, got " + tips.size());
        assertEquals(tips.size(), tips.stream().map(UsageTips.Tip::title).distinct().count(), "duplicate tip titles");
        for (UsageTips.Tip tip : tips) {
            assertFalse(tip.title().isBlank());
            assertFalse(tip.bodyHtml().isBlank(), tip.title());
            assertFalse(tip.title().contains("<"), "titles are plain text: " + tip.title());
        }
    }
}
