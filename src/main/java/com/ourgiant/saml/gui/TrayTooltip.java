package com.ourgiant.saml.gui;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Text for the tray icon's tooltip (#225): the soonest-relevant credential countdowns, so hovering
 * the icon answers "how long have I got?" without opening anything. Pure, so the selection and
 * length rules are testable.
 */
final class TrayTooltip {

    static final String TITLE = "AWS IdP SAML UI";
    /** Windows truncates tray tooltips beyond this (NOTIFYICONDATA.szTip is 128 chars including the terminator). */
    static final int MAX_LENGTH = 127;
    static final int MAX_ENTRIES = 3;
    private static final int MAX_NAME_LENGTH = 20;
    private static final String SEPARATOR = " · ";

    private TrayTooltip() {
    }

    /**
     * @param pinnedInOrder pinned profiles, top first; shown in that order when there are any
     * @param expirations   saved credential expiry per profile that has credentials (absent = none)
     * @param knownProfiles profiles that exist; a pinned name that no longer does is skipped
     */
    static String build(List<String> pinnedInOrder, Map<String, Instant> expirations, List<String> knownProfiles, Instant now) {
        List<String> chosen = new ArrayList<>();
        for (String profile : pinnedInOrder) {
            if (knownProfiles.contains(profile)) {
                chosen.add(profile);
            }
        }
        if (chosen.isEmpty()) {
            expirations.keySet().stream()
                .filter(knownProfiles::contains)
                .sorted(Comparator.comparing(expirations::get))
                .forEach(chosen::add);
        }
        if (chosen.isEmpty()) {
            return TITLE;
        }

        List<String> entries = new ArrayList<>();
        for (String profile : chosen) {
            entries.add(shorten(profile) + ": " + describe(expirations.get(profile), now));
        }
        // Show as many entries as fit both MAX_ENTRIES and the platform's length limit; never cut one mid-way.
        for (int shown = Math.min(MAX_ENTRIES, entries.size()); shown >= 1; shown--) {
            String text = TITLE + "\n" + String.join(SEPARATOR, entries.subList(0, shown))
                + (entries.size() > shown ? SEPARATOR + "+" + (entries.size() - shown) + " more" : "");
            if (text.length() <= MAX_LENGTH) {
                return text;
            }
        }
        String only = TITLE + "\n" + entries.get(0);
        return only.length() <= MAX_LENGTH ? only : only.substring(0, MAX_LENGTH - 1) + "…";
    }

    static String describe(Instant expiration, Instant now) {
        if (expiration == null) {
            return "no credentials";
        }
        Duration remaining = Duration.between(now, expiration);
        if (remaining.isNegative() || remaining.isZero()) {
            return "expired";
        }
        long minutes = remaining.toMinutes();
        if (minutes < 1) {
            return "<1m left";
        }
        if (minutes < 60) {
            return minutes + "m left";
        }
        return remaining.toHours() + "h " + remaining.toMinutesPart() + "m left";
    }

    private static String shorten(String name) {
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH - 1) + "…";
    }
}
