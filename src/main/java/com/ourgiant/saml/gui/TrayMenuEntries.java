package com.ourgiant.saml.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the system tray menu's per-profile section shows (#195): one entry per pinned profile, in
 * pin order, with a status-bearing label and which actions are currently possible. Kept apart
 * from the AWT {@code PopupMenu} so the rules are testable, and so the menu is rebuilt only when
 * this actually changes (rebuilding an open native tray menu on every 30s poll would flicker).
 */
record TrayMenuEntries(List<Entry> profiles, boolean requestInProgress) {

    record Entry(String profile, String label, boolean canRequest, boolean canOpenConsole) {
    }

    /**
     * @param pinnedInOrder   pinned profiles, top first
     * @param statuses        status-table status by profile (VALID / EXPIRED / UNKNOWN)
     * @param expiringSoon    profiles inside the expiry warning window
     * @param withCredentials profiles that have saved credentials (Open Console needs them)
     * @param knownProfiles   profiles that still exist; a pinned name that no longer does is skipped
     */
    static TrayMenuEntries build(List<String> pinnedInOrder, Map<String, String> statuses, Set<String> expiringSoon,
                                 Set<String> withCredentials, Set<String> knownProfiles, boolean requestInProgress) {
        List<Entry> entries = new ArrayList<>();
        for (String profile : pinnedInOrder) {
            if (!knownProfiles.contains(profile)) {
                continue;
            }
            String status = statuses.get(profile);
            String suffix;
            if (expiringSoon.contains(profile)) {
                suffix = "expiring soon";
            } else if ("VALID".equals(status)) {
                suffix = "valid";
            } else if ("EXPIRED".equals(status)) {
                suffix = "expired";
            } else {
                suffix = "no credentials";
            }
            entries.add(new Entry(profile, profile + " — " + suffix, !requestInProgress, withCredentials.contains(profile)));
        }
        return new TrayMenuEntries(List.copyOf(entries), requestInProgress);
    }
}
