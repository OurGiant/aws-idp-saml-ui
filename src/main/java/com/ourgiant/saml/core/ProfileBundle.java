package com.ourgiant.saml.core;

import org.ini4j.Config;
import org.ini4j.Ini;
import org.ini4j.Profile;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A shareable snapshot of profiles and app settings (#199): every samlsts section plus the
 * non-secret, machine-independent app settings and pinned order. Never holds credentials,
 * the stored Okta password or its encryption key — {@link #EXPORTED_SETTINGS} is an allow-list
 * for exactly that reason, and samlsts keys that look secret are dropped too.
 *
 * <p>Stored as INI, like samlsts itself, so it can be read and hand-edited:
 * <pre>
 * [export]           format, version, app_version, exported_at
 * [settings]         allow-listed app settings
 * [pinned]           1 = first-pinned-profile, 2 = ...
 * [samlsts global]   one "samlsts &lt;name&gt;" section per samlsts section
 * </pre>
 * The "samlsts " prefix keeps a profile named e.g. "settings" from colliding with the file's own sections.
 */
public record ProfileBundle(String appVersion, String exportedAt, Map<String, Map<String, String>> samlstsSections,
                            Map<String, String> settings, List<String> pinnedProfiles) {

    static final String FORMAT = "aws-idp-saml-ui-profiles";
    static final int VERSION = 1;
    private static final String SAMLSTS_PREFIX = "samlsts ";

    /**
     * App settings that travel with an export. Deliberately excludes the stored password and its
     * key, store_password_enabled (it would silently start storing the password on another
     * machine), and machine-specific state like window bounds and the last-used profile.
     */
    public static final Set<String> EXPORTED_SETTINGS = Set.of(
        "session_duration", "password_expiration_minutes", "theme", "browser", "use_fastpass",
        "tray_notifications_enabled", "auto_renew_pinned_profiles_enabled", "start_minimized_to_tray",
        "write_region_to_aws_config", "credential_export_format");

    public ProfileBundle {
        samlstsSections = copyNested(samlstsSections);
        settings = Map.copyOf(settings);
        pinnedProfiles = List.copyOf(pinnedProfiles);
    }

    /** Builds a bundle from live config, dropping samlsts keys that look like secrets and non-allow-listed settings. */
    public static ProfileBundle of(String appVersion, Map<String, Map<String, String>> samlstsSections,
                                   Map<String, String> allSettings, List<String> pinnedProfiles) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        samlstsSections.forEach((name, fields) -> {
            Map<String, String> kept = new LinkedHashMap<>();
            fields.forEach((key, value) -> {
                if (!looksSecret(key) && value != null) {
                    kept.put(key, value);
                }
            });
            sections.put(name, kept);
        });
        Map<String, String> settings = new LinkedHashMap<>();
        allSettings.forEach((key, value) -> {
            if (EXPORTED_SETTINGS.contains(key) && value != null && !value.isEmpty()) {
                settings.put(key, value);
            }
        });
        return new ProfileBundle(appVersion, Instant.now().toString(), sections, settings, pinnedProfiles);
    }

    static boolean isUsableSetting(String key, String value) {
        return switch (key) {
            case "session_duration", "password_expiration_minutes" -> value.matches("\\d{1,7}") && Integer.parseInt(value) > 0;
            case "use_fastpass", "tray_notifications_enabled", "auto_renew_pinned_profiles_enabled",
                 "start_minimized_to_tray", "write_region_to_aws_config" -> value.equals("true") || value.equals("false");
            case "browser" -> Set.of("chrome", "firefox", "edge").contains(value);
            case "credential_export_format" -> java.util.Arrays.stream(CredentialExportFormat.values()).anyMatch(f -> f.name().equals(value));
            case "theme" -> !value.isBlank(); // checked against the installed themes when applied (ThemeManager falls back)
            default -> false;
        };
    }

    static boolean looksSecret(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password") || k.contains("secret") || k.contains("token");
    }

    /** Profile sections only (not global or identity providers). */
    public Map<String, Map<String, String>> profiles() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        samlstsSections.forEach((name, fields) -> {
            if (!ConfigSections.isSettingsSection(name)) {
                result.put(name, fields);
            }
        });
        return result;
    }

    public void write(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        String nl = System.lineSeparator();
        out.append("; AWS IdP SAML UI profile export. Contains no credentials, passwords or encryption keys.").append(nl);
        out.append("; Import it via File > Import Profiles...").append(nl).append(nl);
        out.append("[export]").append(nl)
            .append("format = ").append(FORMAT).append(nl)
            .append("version = ").append(VERSION).append(nl)
            .append("app_version = ").append(appVersion).append(nl)
            .append("exported_at = ").append(exportedAt).append(nl);
        appendSection(out, "settings", settings, nl);
        Map<String, String> pinned = new LinkedHashMap<>();
        for (int i = 0; i < pinnedProfiles.size(); i++) {
            pinned.put(String.valueOf(i + 1), pinnedProfiles.get(i));
        }
        appendSection(out, "pinned", pinned, nl);
        samlstsSections.forEach((name, fields) -> appendSection(out, SAMLSTS_PREFIX + name, fields, nl));
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    private static void appendSection(StringBuilder out, String name, Map<String, String> fields, String nl) {
        out.append(nl).append('[').append(name).append(']').append(nl);
        fields.forEach((key, value) -> out.append(key).append(" = ").append(value).append(nl));
    }

    /** @throws IOException if the file can't be read or isn't a profile export this version understands */
    public static ProfileBundle read(Path file) throws IOException {
        Ini ini = new Ini();
        Config config = ini.getConfig().clone();
        config.setEscape(false); // same reason as ConfigManager.disableValueEscaping (#89)
        ini.setConfig(config);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ini.load(reader);
        } catch (IOException e) {
            throw new IOException("Couldn't read " + file.getFileName() + ": " + e.getMessage(), e);
        }

        Profile.Section header = ini.get("export");
        if (header == null || !FORMAT.equals(header.get("format"))) {
            throw new IOException(file.getFileName() + " isn't an AWS IdP SAML UI profile export.");
        }
        int version;
        try {
            version = Integer.parseInt(header.get("version", "").trim());
        } catch (NumberFormatException e) {
            throw new IOException(file.getFileName() + " has an invalid export version.", e);
        }
        if (version > VERSION) {
            throw new IOException(file.getFileName() + " was exported by a newer version of the app (format "
                + version + "); update this app to import it.");
        }

        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        for (String name : ini.keySet()) {
            if (name.startsWith(SAMLSTS_PREFIX) && name.length() > SAMLSTS_PREFIX.length()) {
                sections.put(name.substring(SAMLSTS_PREFIX.length()), sectionMap(ini.get(name)));
            }
        }
        Map<String, String> settings = new LinkedHashMap<>();
        sectionMap(ini.get("settings")).forEach((key, value) -> {
            // Never import anything outside the allow-list, or a value the app can't use, even if hand-edited.
            if (EXPORTED_SETTINGS.contains(key) && isUsableSetting(key, value)) {
                settings.put(key, value);
            }
        });
        List<String> pinned = new ArrayList<>();
        Map<String, String> pinnedSection = sectionMap(ini.get("pinned"));
        pinnedSection.entrySet().stream()
            .filter(e -> e.getKey().matches("\\d+"))
            .sorted((a, b) -> Integer.compare(Integer.parseInt(a.getKey()), Integer.parseInt(b.getKey())))
            .forEach(e -> pinned.add(e.getValue()));
        return new ProfileBundle(header.get("app_version", "unknown"), header.get("exported_at", ""), sections, settings, pinned);
    }

    private static Map<String, String> sectionMap(Profile.Section section) {
        Map<String, String> map = new LinkedHashMap<>();
        if (section != null) {
            for (String key : section.keySet()) {
                String value = section.get(key);
                map.put(key, value == null ? "" : value.trim());
            }
        }
        return map;
    }

    private static Map<String, Map<String, String>> copyNested(Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(k, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(v))));
        return java.util.Collections.unmodifiableMap(copy);
    }
}
