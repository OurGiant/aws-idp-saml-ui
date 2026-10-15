package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileBundleTest {

    private static Map<String, Map<String, String>> sections() {
        Map<String, Map<String, String>> s = new LinkedHashMap<>();
        s.put("global", new LinkedHashMap<>(Map.of("username", "jdoe@example.com", "okta_password", "hunter2")));
        s.put("Fed-Okta", new LinkedHashMap<>(Map.of("loginpage", "https://example.okta.com/app/x/sso/saml", "logintitle", "Example")));
        s.put("dev", new LinkedHashMap<>(Map.of("accountnumber", "111111111111", "iamrole", "Dev", "samlprovider", "Fed-Okta",
            "guiname", "Dev", "client_secret", "abc")));
        s.put("settings", new LinkedHashMap<>(Map.of("accountnumber", "222222222222", "iamrole", "Ops", "guiname", "Ops")));
        return s;
    }

    @Test
    void of_dropsSecretLookingKeysAndNonAllowListedSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("theme", "Flat Dark");
        settings.put("okta_password", "encrypted");
        settings.put("password_encryption_key", "key");
        settings.put("store_password_enabled", "true");
        settings.put("main_window_bounds", "1,2,3,4,false");
        settings.put("browser", null);

        ProfileBundle bundle = ProfileBundle.of("1.0.0", sections(), settings, List.of("dev"));

        assertEquals(Map.of("theme", "Flat Dark"), bundle.settings());
        assertFalse(bundle.samlstsSections().get("global").containsKey("okta_password"));
        assertFalse(bundle.samlstsSections().get("dev").containsKey("client_secret"));
        assertEquals("jdoe@example.com", bundle.samlstsSections().get("global").get("username"));
    }

    @Test
    void writeThenRead_roundTripsEverythingIncludingUrlsAndAProfileNamedSettings(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("export.ini");
        ProfileBundle written = ProfileBundle.of("1.6.15", sections(),
            Map.of("theme", "Flat Dark", "session_duration", "3600", "use_fastpass", "true"), List.of("settings", "dev"));
        written.write(file);

        String text = Files.readString(file);
        assertTrue(text.contains("loginpage = https://example.okta.com/app/x/sso/saml"), text);
        assertFalse(text.contains("\\:"), "no ini4j escaping (#89):\n" + text);
        assertFalse(text.contains("hunter2") || text.contains("client_secret"), text);

        ProfileBundle read = ProfileBundle.read(file);
        assertEquals("1.6.15", read.appVersion());
        assertEquals(written.samlstsSections(), read.samlstsSections());
        assertEquals(written.settings(), read.settings());
        assertEquals(List.of("settings", "dev"), read.pinnedProfiles());
        assertEquals(List.of("dev", "settings"), List.copyOf(read.profiles().keySet()));
    }

    @Test
    void read_ignoresHandAddedSecretsAndUnusableValues(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("export.ini");
        Files.writeString(file, """
            [export]
            format = aws-idp-saml-ui-profiles
            version = 1
            [settings]
            okta_password = injected
            store_password_enabled = true
            browser = netscape
            session_duration = -5
            password_expiration_minutes = 60
            use_fastpass = yes
            tray_notifications_enabled = false
            credential_export_format = FISH
            expiry_warning_minutes = 7
            [samlsts dev]
            accountnumber = 111111111111
            """);

        ProfileBundle bundle = ProfileBundle.read(file);

        assertEquals(Map.of("password_expiration_minutes", "60", "tray_notifications_enabled", "false"), bundle.settings());
        assertEquals(Map.of("accountnumber", "111111111111"), bundle.profiles().get("dev"));
    }

    @Test
    void expiryWarningMinutes_travelsWithExportWhenValid(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("export.ini");
        ProfileBundle.of("1.0.0", sections(), Map.of("expiry_warning_minutes", "15"), List.of()).write(file);

        assertEquals("15", ProfileBundle.read(file).settings().get("expiry_warning_minutes"));
    }

    @Test
    void read_rejectsFilesThatAreNotExportsOrAreTooNew(@TempDir Path dir) throws Exception {
        Path samlsts = dir.resolve("samlsts");
        Files.writeString(samlsts, "[global]\nusername = x\n");
        assertTrue(assertThrows(IOException.class, () -> ProfileBundle.read(samlsts)).getMessage().contains("isn't an AWS IdP SAML UI profile export"));

        Path newer = dir.resolve("newer.ini");
        Files.writeString(newer, "[export]\nformat = aws-idp-saml-ui-profiles\nversion = 99\n");
        assertTrue(assertThrows(IOException.class, () -> ProfileBundle.read(newer)).getMessage().contains("newer version"));

        Path binary = dir.resolve("garbage.ini");
        Files.write(binary, new byte[]{0, 1, 2, '[', '['});
        assertThrows(IOException.class, () -> ProfileBundle.read(binary));
    }
}
