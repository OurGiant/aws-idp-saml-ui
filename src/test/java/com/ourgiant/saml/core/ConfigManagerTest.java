package com.ourgiant.saml.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
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
    private ConfigManager configManager;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        // ConfigManager's DatabaseManager keeps its SQLite connection open for the manager's
        // lifetime; closing it here releases the file lock so @TempDir can delete it (Windows
        // fails the whole test run otherwise - it errors on open file handles, unlike Linux/macOS).
        if (configManager != null) {
            Field field = ConfigManager.class.getDeclaredField("databaseManager");
            field.setAccessible(true);
            ((DatabaseManager) field.get(configManager)).close();
        }
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

        configManager = new ConfigManager();
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

        configManager = new ConfigManager();
        assertEquals("https://example.okta.com/app/foo/sso/saml",
                configManager.getProfile("Fed-OKTA").get("loginpage"));

        configManager.saveProfile("Fed-OKTA", Map.of("logintitle", "Fed OKTA Login"));

        String written = readSamlsts();
        assertFalse(written.contains("\\:"), "previously-escaped colon should be healed on next save:\n" + written);
    }

    @Test
    void createConfig_doesNotEscapeColonsInUrls() throws Exception {
        configManager = new ConfigManager(false);

        Map<String, String> global = new LinkedHashMap<>();
        global.put("idp_entry_url", "https://example.okta.com/app/example/sso/saml");
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        sections.put("global", global);

        configManager.createConfig(sections);

        String written = readSamlsts();
        assertFalse(written.contains("\\:"), "created samlsts should not contain escaped colons:\n" + written);
        assertTrue(written.contains("https://example.okta.com/app/example/sso/saml"));
    }

    @Test
    void profileScopedGetters_preferProfileOverGlobal() throws Exception {
        writeSamlsts("""
                [global]
                accountnumber = 000000000000
                iamrole = GlobalRole
                samlprovider = Fed-Global

                [my-profile]
                accountnumber = 111111111111
                iamrole = ProfileRole
                samlprovider = Fed-Profile
                """);

        configManager = new ConfigManager();

        assertEquals("111111111111", configManager.getAccountNumber("my-profile"));
        assertEquals("ProfileRole", configManager.getIamRole("my-profile"));
        assertEquals("Fed-Profile", configManager.getSamlProvider("my-profile"));
    }

    @Test
    void profileScopedGetters_fallBackToGlobalWhenProfileOmitsKey() throws Exception {
        writeSamlsts("""
                [global]
                accountnumber = 000000000000
                iamrole = GlobalRole
                samlprovider = Fed-Global
                awsregion = us-west-2

                [my-profile]
                """);

        configManager = new ConfigManager();

        assertEquals("000000000000", configManager.getAccountNumber("my-profile"));
        assertEquals("GlobalRole", configManager.getIamRole("my-profile"));
        assertEquals("Fed-Global", configManager.getSamlProvider("my-profile"));
        assertEquals("us-west-2", configManager.getAwsRegion("my-profile"));
    }

    @Test
    void getAwsRegion_defaultsToUsEast1WhenUnset() throws Exception {
        writeSamlsts("""
                [global]
                idp_entry_url = https://example.okta.com/app/example/sso/saml

                [my-profile]
                """);

        configManager = new ConfigManager();

        assertEquals("us-east-1", configManager.getAwsRegion("my-profile"));
    }
}
