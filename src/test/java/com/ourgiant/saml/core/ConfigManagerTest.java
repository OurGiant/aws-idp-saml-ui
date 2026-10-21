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
    void saveProfile_persistsGuiName() throws Exception {
        writeSamlsts("""
                [global]
                idp_entry_url = https://example.okta.com/app/example/sso/saml

                [Fed-OKTA]
                loginpage = https://example.okta.com/app/foo/sso/saml
                """);

        configManager = new ConfigManager();
        configManager.saveProfile("my-profile", Map.of("guiname", "Production Account"));

        assertEquals("Production Account", configManager.getGuiName("my-profile"));
        assertTrue(readSamlsts().contains("guiname = Production Account"));
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
    void getDefaultUsername_usesGlobalUsername() throws Exception {
        writeSamlsts("""
                [global]
                username = jdoe@example.com

                [some-profile]
                username = other@example.com
                """);

        configManager = new ConfigManager();

        assertEquals("jdoe@example.com", configManager.getDefaultUsername());
    }

    @Test
    void getSessionDuration_profileOverrideWinsOverGlobalDefault() throws Exception {
        writeSamlsts("""
                [global]
                sessionduration = 1800

                [short]
                sessionduration = 3600

                [inherits]
                accountnumber = 111111111111

                [broken]
                sessionduration = soon
                """);
        configManager = new ConfigManager();
        Field field = ConfigManager.class.getDeclaredField("databaseManager");
        field.setAccessible(true);
        ((DatabaseManager) field.get(configManager)).setSessionDuration(7200);

        assertEquals(3600, configManager.getSessionDuration("short"));
        assertEquals(Integer.valueOf(3600), configManager.getProfileSessionDuration("short"));
        assertEquals(7200, configManager.getSessionDuration("inherits"), "no override: the Configuration default");
        assertEquals(null, configManager.getProfileSessionDuration("inherits"));
        assertEquals(7200, configManager.getSessionDuration("broken"), "an invalid override falls back to the default");
        assertEquals(7200, configManager.getDefaultSessionDuration());
    }

    @Test
    void saveProfile_canRemoveAnOverride() throws Exception {
        writeSamlsts("""
                [global]
                username = x

                [p]
                accountnumber = 111111111111
                sessionduration = 3600
                """);
        configManager = new ConfigManager();

        configManager.saveProfile("p", Map.of("guiname", "P"), java.util.Set.of("sessionduration"));

        assertEquals(null, configManager.getProfileSessionDuration("p"));
        String written = readSamlsts();
        assertFalse(written.contains("sessionduration"), written);
        assertTrue(written.contains("guiname = P") && written.contains("accountnumber = 111111111111"), written);
    }

    @Test
    void explicitRegionAndConsoleService_haveNoDefaults() throws Exception {
        writeSamlsts("""
                [global]
                awsregion = eu-west-1

                [inherits]
                accountnumber = 111111111111

                [own]
                awsregion = ap-south-1
                consoleservice = ec2

                [blank]
                consoleservice =
                """);
        configManager = new ConfigManager();

        assertEquals("eu-west-1", configManager.getExplicitAwsRegion("inherits"));
        assertEquals("ap-south-1", configManager.getExplicitAwsRegion("own"));
        assertEquals("ec2", configManager.getConsoleService("own"));
        assertEquals(null, configManager.getConsoleService("inherits"));
        assertEquals(null, configManager.getConsoleService("blank"));
    }

    @Test
    void explicitRegion_isNullWhenNothingConfigured() throws Exception {
        writeSamlsts("""
                [global]
                username = x

                [p]
                accountnumber = 1
                """);
        configManager = new ConfigManager();

        assertEquals(null, configManager.getExplicitAwsRegion("p"));
        assertEquals("us-east-1", configManager.getAwsRegion("p"), "the STS-call region still has its fallback");
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

    @Test
    void saveGlobalSettings_setsUsernameAndRegion_profileOverridesKept() throws Exception {
        writeSamlsts("""
                [global]
                username = old
                awsregion = us-east-1

                [inherits]
                accountnumber = 1

                [own]
                accountnumber = 2
                username = someone-else
                awsregion = eu-west-1
                """);
        configManager = new ConfigManager();

        configManager.saveGlobalSettings("  jdoe@acme.com ", "us-west-2");

        assertEquals("jdoe@acme.com", configManager.getDefaultUsername());
        assertEquals("us-west-2", configManager.getExplicitAwsRegion("inherits"));
        assertEquals("someone-else", configManager.getUsername("own"));
        assertEquals("eu-west-1", configManager.getExplicitAwsRegion("own"));
        String written = readSamlsts();
        assertTrue(written.contains("username = jdoe@acme.com"));
        assertTrue(written.contains("awsregion = us-west-2"));
    }

    @Test
    void saveGlobalSettings_blankRegionRemovesDefault() throws Exception {
        writeSamlsts("""
                [global]
                username = jdoe
                awsregion = us-west-2
                """);
        configManager = new ConfigManager();

        configManager.saveGlobalSettings("jdoe", null);

        assertEquals(null, configManager.getExplicitAwsRegion(null));
        assertFalse(readSamlsts().contains("awsregion"));
    }

    @Test
    void saveGlobalSettings_requiresUsername_andCreatesGlobalIfMissing() throws Exception {
        writeSamlsts("""
                [p]
                accountnumber = 1
                """);
        configManager = new ConfigManager();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> configManager.saveGlobalSettings(" ", "us-east-1"));

        configManager.saveGlobalSettings("jdoe", "ap-south-1");
        assertEquals("jdoe", configManager.getDefaultUsername());
        assertTrue(readSamlsts().contains("[global]"));
    }

    @Test
    void notYetLoaded_behavesAsEmptyUntilCreateConfig() throws Exception {
        configManager = new ConfigManager(false);

        assertTrue(configManager.getIdpProviders().isEmpty());
        assertTrue(configManager.getAvailableProfiles().isEmpty());
        assertEquals(null, configManager.validateProviderName("Okta", null));
        assertEquals(null, configManager.getDefaultProvider());

        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        sections.put("global", new LinkedHashMap<>(Map.of("username", "jdoe")));
        configManager.createConfig(sections);
        configManager.saveProvider(null, "Okta", Map.of("loginpage", "https://acme.okta.com/x", "logintitle", "Okta"));

        assertEquals(java.util.List.of("Fed-Okta"), configManager.getIdpProviders());
        assertTrue(readSamlsts().contains("username = jdoe"));
    }
}
