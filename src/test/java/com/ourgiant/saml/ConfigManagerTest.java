package com.ourgiant.saml;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {

    @TempDir
    Path tempHome;

    private String originalUserHome;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalUserHome);
    }

    private void writeSamlsts(String content) throws IOException {
        Path awsDir = tempHome.resolve(".aws");
        Files.createDirectories(awsDir);
        Files.writeString(awsDir.resolve("samlsts"), content);
    }

    private String readSamlsts() throws IOException {
        return Files.readString(tempHome.resolve(".aws").resolve("samlsts"));
    }

    @Test
    void saveProfile_doesNotEscapeColonsInUrls() throws Exception {
        writeSamlsts("""
                [global]
                idp_entry_url = https://example.okta.com/app/example/sso/saml

                [Fed-OKTA]
                loginpage = https://example.okta.com/app/foo/sso/saml
                """);

        ConfigManager configManager = new ConfigManager();
        configManager.saveProfile("Fed-OKTA", Map.of("logintitle", "Fed OKTA Login"));

        String written = readSamlsts();
        assertFalse(written.contains("\\:"), "saved samlsts should not contain escaped colons:\n" + written);
        assertTrue(written.contains("https://example.okta.com/app/foo/sso/saml"));
    }

    @Test
    void saveProfile_selfHealsPreviouslyEscapedColons() throws Exception {
        // Reproduces the bug in #89: a samlsts file already corrupted by the escaping bug.
        writeSamlsts("""
                [global]
                idp_entry_url = https://example.okta.com/app/example/sso/saml

                [Fed-OKTA]
                loginpage = https\\://example.okta.com/app/foo/sso/saml
                """);

        ConfigManager configManager = new ConfigManager();
        assertEquals("https://example.okta.com/app/foo/sso/saml",
                configManager.getProfile("Fed-OKTA").get("loginpage"));

        configManager.saveProfile("Fed-OKTA", Map.of("logintitle", "Fed OKTA Login"));

        String written = readSamlsts();
        assertFalse(written.contains("\\:"), "previously-escaped colon should be healed on next save:\n" + written);
    }

    @Test
    void createConfig_doesNotEscapeColonsInUrls() throws Exception {
        ConfigManager configManager = new ConfigManager(false);

        Map<String, String> global = new LinkedHashMap<>();
        global.put("idp_entry_url", "https://example.okta.com/app/example/sso/saml");
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        sections.put("global", global);

        configManager.createConfig(sections);

        String written = readSamlsts();
        assertFalse(written.contains("\\:"), "created samlsts should not contain escaped colons:\n" + written);
        assertTrue(written.contains("https://example.okta.com/app/example/sso/saml"));
    }
}
