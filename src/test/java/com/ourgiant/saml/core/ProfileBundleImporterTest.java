package com.ourgiant.saml.core;

import com.ourgiant.saml.core.ProfileBundleImporter.Choice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileBundleImporterTest {

    @TempDir
    Path tempHome;

    private String originalUserHome;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;

    @BeforeEach
    void setUp() throws Exception {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".aws"));
        Files.writeString(tempHome.resolve(".aws/samlsts"), """
            [global]
            username = me@example.com

            [Fed-Okta]
            loginpage = https://mine.okta.com/app/sso/saml
            logintitle = Mine

            [dev]
            accountnumber = 111111111111
            iamrole = Dev
            samlprovider = Fed-Okta
            guiname = My Dev
            """);
        configManager = new ConfigManager();
        databaseManager = new DatabaseManager();
        databaseManager.setPinnedProfilesInOrder(List.of("dev"));
    }

    @AfterEach
    void tearDown() throws Exception {
        Field field = ConfigManager.class.getDeclaredField("databaseManager");
        field.setAccessible(true);
        ((DatabaseManager) field.get(configManager)).close();
        databaseManager.close();
        System.setProperty("user.home", originalUserHome);
    }

    private static ProfileBundle bundle() {
        Map<String, Map<String, String>> s = new LinkedHashMap<>();
        s.put("global", Map.of("username", "someone-else@example.com", "awsregion", "eu-west-1"));
        s.put("Fed-Okta", Map.of("loginpage", "https://theirs.okta.com/app/sso/saml", "logintitle", "Theirs"));
        s.put("Fed-Azure", Map.of("loginpage", "https://login.microsoftonline.com/x", "logintitle", "Azure"));
        s.put("dev", Map.of("accountnumber", "999999999999", "iamrole", "TheirDev", "samlprovider", "Fed-Okta", "guiname", "Their Dev"));
        s.put("prod", Map.of("accountnumber", "222222222222", "iamrole", "Prod", "samlprovider", "Fed-Azure", "guiname", "Prod"));
        return new ProfileBundle("1.0.0", "", s, Map.of("theme", "Flat Light", "browser", "firefox"), List.of("prod", "dev"));
    }

    @Test
    void preview_listsProvidersToAddKeptAndMissingGlobalKeys() {
        ProfileBundleImporter.Preview preview = ProfileBundleImporter.preview(bundle(), configManager.getAllSections());

        assertEquals(List.of("Fed-Azure"), preview.providersToAdd());
        assertEquals(List.of("Fed-Okta"), preview.providersKeptDifferent());
        assertEquals(List.of("awsregion"), preview.globalKeysToAdd());
    }

    @Test
    void apply_importsNewAndRenamedProfiles_neverTouchingExistingProviderOrGlobalValues() throws Exception {
        ProfileBundleImporter.Result result = ProfileBundleImporter.apply(bundle(),
            List.of(new Choice("prod", "prod"), new Choice("dev", "their-dev")), true, configManager, databaseManager);

        assertEquals(List.of("prod", "their-dev"), result.importedProfiles());
        assertTrue(result.overwrittenProfiles().isEmpty());
        assertEquals("111111111111", configManager.getAccountNumber("dev"), "existing dev untouched");
        assertEquals("999999999999", configManager.getAccountNumber("their-dev"));
        assertEquals("Fed-Azure", configManager.getSamlProvider("prod"));
        assertEquals("https://mine.okta.com/app/sso/saml", configManager.getProvider("Fed-Okta").get("loginpage"));
        assertEquals("https://login.microsoftonline.com/x", configManager.getProvider("Fed-Azure").get("loginpage"));
        assertEquals("me@example.com", configManager.getDefaultUsername());
        assertEquals("eu-west-1", configManager.getAwsRegion("prod"));

        assertEquals("Flat Light", result.importedTheme());
        assertEquals("firefox", databaseManager.getConfig("browser"));
        assertEquals(List.of("dev", "prod", "their-dev"), databaseManager.getPinnedProfilesInOrder(),
            "existing pins first, then the file's pins in order, following the dev -> their-dev rename");

        // persisted, not just in memory
        String written = Files.readString(tempHome.resolve(".aws/samlsts"));
        assertTrue(written.contains("[their-dev]") && written.contains("[Fed-Azure]"), written);
        assertFalse(written.contains("someone-else"), written);
    }

    @Test
    void apply_overwritesWhenChosenAndSkipsSettingsWhenNotRequested() throws Exception {
        databaseManager.setConfig("browser", "chrome");

        ProfileBundleImporter.Result result = ProfileBundleImporter.apply(bundle(), List.of(new Choice("dev", "dev")),
            false, configManager, databaseManager);

        assertEquals(List.of("dev"), result.overwrittenProfiles());
        assertEquals("999999999999", configManager.getAccountNumber("dev"));
        assertEquals("Their Dev", configManager.getGuiName("dev"));
        assertEquals("chrome", databaseManager.getConfig("browser"));
        assertNull(result.importedTheme());
        assertEquals(List.of("dev"), databaseManager.getPinnedProfilesInOrder());
    }

    @Test
    void apply_pinsFollowRenames() throws Exception {
        databaseManager.setPinnedProfilesInOrder(List.of());

        ProfileBundleImporter.apply(bundle(), List.of(new Choice("dev", "their-dev"), new Choice("prod", "prod")),
            true, configManager, databaseManager);

        assertEquals(List.of("prod", "their-dev"), databaseManager.getPinnedProfilesInOrder());
    }

    @Test
    void validate_rules() {
        Set<String> existing = Set.of("dev");
        assertEquals("Select at least one profile to import.", ProfileBundleImporter.validate(List.of(), existing, Set.of()));
        assertNull(ProfileBundleImporter.validate(List.of(new Choice("prod", "prod")), existing, Set.of()));
        assertTrue(ProfileBundleImporter.validate(List.of(new Choice("dev", "dev")), existing, Set.of()).contains("already exists"));
        assertNull(ProfileBundleImporter.validate(List.of(new Choice("dev", "dev")), existing, Set.of("dev")));
        assertTrue(ProfileBundleImporter.validate(List.of(new Choice("dev", "my dev")), existing, Set.of()).contains("can only contain"));
        assertNull(ProfileBundleImporter.validate(List.of(new Choice("odd name", "odd name")), existing, Set.of()),
            "an unrenamed profile keeps whatever name worked in samlsts");
        assertTrue(ProfileBundleImporter.validate(List.of(new Choice("x", "Fed-x")), existing, Set.of()).contains("reserved"));
        assertTrue(ProfileBundleImporter.validate(List.of(new Choice("a", "z"), new Choice("b", "z")), existing, Set.of()).contains("more than one"));
        assertTrue(ProfileBundleImporter.validate(List.of(new Choice("a", " ")), existing, Set.of()).contains("needs a name"));
        assertEquals(List.of("dev"), ProfileBundleImporter.overwrites(List.of(new Choice("dev", "dev"), new Choice("p", "p")), existing));
    }
}
