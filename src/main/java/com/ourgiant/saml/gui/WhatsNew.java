package com.ourgiant.saml.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Release notes bundled in the jar (#210): {@code whats-new/<version>.html}, indexed by
 * {@code whats-new/versions.txt} (resources inside a jar can't be listed). Notes are written as
 * part of cutting a release, so the dialog works offline and always matches the installed version.
 */
final class WhatsNew {
    private static final Logger logger = LoggerFactory.getLogger(WhatsNew.class);
    static final String RELEASES_URL = "https://github.com/OurGiant/aws-idp-saml-ui/releases";

    private WhatsNew() {
    }

    /** Versions with bundled notes, as listed in versions.txt (blank lines and # comments ignored). */
    static List<String> bundledVersions() {
        String index = readResource("/whats-new/versions.txt");
        List<String> versions = new ArrayList<>();
        if (index != null) {
            for (String line : index.split("\\R")) {
                String version = line.trim();
                if (!version.isEmpty() && !version.startsWith("#")) {
                    versions.add(version);
                }
            }
        }
        return versions;
    }

    /**
     * The bundled versions to show after an upgrade, newest first: newer than {@code lastSeen} and
     * no newer than {@code current}, so a user who skipped releases sees everything they missed.
     * Empty when the running version isn't a real release version (e.g. "unknown" in development).
     */
    static List<String> versionsToShow(List<String> bundled, String lastSeen, String current) {
        List<String> result = new ArrayList<>();
        if (current == null || !current.matches("\\d+(\\.\\d+)*")) {
            return result;
        }
        for (String version : bundled) {
            boolean released = !AboutDialog.isNewerVersion(version, current);
            boolean unseen = lastSeen == null || AboutDialog.isNewerVersion(version, lastSeen);
            if (released && unseen) {
                result.add(version);
            }
        }
        result.sort((a, b) -> AboutDialog.isNewerVersion(a, b) ? -1 : AboutDialog.isNewerVersion(b, a) ? 1 : 0);
        return result;
    }

    /** The newest bundled version no newer than {@code current}, or null (for Help → Release Notes). */
    static String latestNotesFor(List<String> bundled, String current) {
        List<String> all = versionsToShow(bundled, null, current);
        return all.isEmpty() ? null : all.get(0);
    }

    /** The notes for each version joined into one HTML body, or null if none could be read. */
    static String notesHtml(List<String> versions) {
        StringBuilder html = new StringBuilder();
        for (String version : versions) {
            String notes = readResource("/whats-new/" + version + ".html");
            if (notes != null) {
                if (!html.isEmpty()) {
                    html.append("<hr>");
                }
                html.append(notes);
            }
        }
        return html.isEmpty() ? null : html.toString();
    }

    private static String readResource(String path) {
        try (InputStream in = WhatsNew.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Could not read bundled resource {}", path, e);
            return null;
        }
    }
}
