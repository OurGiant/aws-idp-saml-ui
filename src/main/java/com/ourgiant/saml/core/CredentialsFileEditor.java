package com.ourgiant.saml.core;

import com.ourgiant.saml.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes profiles' sections from {@code ~/.aws/credentials} (#224) as plain text, so every other
 * section — including static keys a user added by hand — and every comment and blank line stays
 * exactly as it was. (Re-serializing through ini4j would reformat the whole file.)
 */
public final class CredentialsFileEditor {

    private static final Pattern HEADER = Pattern.compile("^\\s*\\[\\s*(.+?)\\s*]\\s*(?:[#;].*)?$");

    private CredentialsFileEditor() {
    }

    /** Result of a text transform: the new content and which of the requested sections were actually present. */
    record Edit(String content, Set<String> removed) {
    }

    static Edit withoutSections(String content, Set<String> sections) {
        String nl = content.contains("\r\n") ? "\r\n" : "\n";
        boolean endsWithNewline = content.endsWith("\n");
        List<String> lines = new ArrayList<>(List.of(content.split("\r?\n", -1)));
        if (endsWithNewline) {
            lines.remove(lines.size() - 1);
        }
        List<String> kept = new ArrayList<>();
        Set<String> removed = new LinkedHashSet<>();
        boolean skipping = false;
        boolean lastSectionRemoved = false;
        // Comment/blank lines at the end of a removed section usually introduce the *next* section
        // (e.g. "; personal account" above [personal]), so they're held and kept if that section is.
        List<String> pending = new ArrayList<>();
        for (String line : lines) {
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                boolean remove = sections.contains(header.group(1));
                if (skipping && !remove) {
                    int first = 0;
                    while (first < pending.size() && pending.get(first).isBlank()) {
                        first++; // the kept text before the removed section already has its own spacing
                    }
                    kept.addAll(pending.subList(first, pending.size()));
                }
                pending.clear();
                skipping = remove;
                lastSectionRemoved = remove;
                if (remove) {
                    removed.add(header.group(1));
                    continue;
                }
            } else if (skipping) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                    pending.add(line);
                } else {
                    pending.clear(); // a key line: anything before it belonged to the removed section
                }
                continue;
            }
            kept.add(line);
        }
        if (removed.isEmpty()) {
            return new Edit(content, removed);
        }
        if (lastSectionRemoved) {
            // Don't leave a trailing run of blank lines where the removed last section used to be.
            while (!kept.isEmpty() && kept.get(kept.size() - 1).isBlank()) {
                kept.remove(kept.size() - 1);
            }
        }
        if (kept.isEmpty()) {
            return new Edit("", removed);
        }
        return new Edit(String.join(nl, kept) + (endsWithNewline || lastSectionRemoved ? nl : ""), removed);
    }

    /** Removes the given sections from the file if present; returns the ones removed. Never creates the file. */
    public static Set<String> removeSections(Path file, Set<String> sections) throws IOException {
        if (sections.isEmpty() || !Files.exists(file)) {
            return Set.of();
        }
        String original = Files.readString(file.toRealPath(), StandardCharsets.UTF_8);
        Edit edit = withoutSections(original, sections);
        if (!edit.removed().isEmpty()) {
            AtomicFiles.writeString(file, edit.content());
        }
        return edit.removed();
    }
}
