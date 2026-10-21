package com.ourgiant.saml.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Identity provider management (#249): add, edit, rename, delete, default provider, name checks. */
class ConfigManagerProvidersTest {

    private static final String SAMPLE = """
            [global]
            username = jdoe
            samlprovider = Fed-Okta

            [Fed-Okta]
            loginpage = https://acme.okta.com/app/aws/sso/saml
            logintitle = Okta
            extra = keep-me

            [Fed-Azure]
            loginpage = https://login.microsoftonline.com/t/saml2
            logintitle = Sign in

            [inherits]
            accountnumber = 111111111111
            iamrole = Dev

            [own-okta]
            accountnumber = 222222222222
            iamrole = Admin
            samlprovider = Fed-Okta

            [azure-one]
            accountnumber = 333333333333
            iamrole = Read
            samlprovider = Fed-Azure
            """;

    @TempDir
    Path tempHome;

    private String originalUserHome;
    private ConfigManager configManager;

    @BeforeEach
    void setUp() throws IOException {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Path awsDir = tempHome.resolve(".aws");
        Files.createDirectories(awsDir);
        Files.writeString(awsDir.resolve("samlsts"), SAMPLE);
        configManager = new ConfigManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Release the SQLite file handle so @TempDir can be deleted on Windows.
        Field field = ConfigManager.class.getDeclaredField("databaseManager");
        field.setAccessible(true);
        ((DatabaseManager) field.get(configManager)).close();
        System.setProperty("user.home", originalUserHome);
    }

    /** A fresh manager, so assertions check what was written to disk, not in-memory state. */
    private ConfigManager reload() throws Exception {
        tearDownManagerOnly();
        configManager = new ConfigManager();
        return configManager;
    }

    private void tearDownManagerOnly() throws Exception {
        Field field = ConfigManager.class.getDeclaredField("databaseManager");
        field.setAccessible(true);
        ((DatabaseManager) field.get(configManager)).close();
    }

    @Test
    void providerNameHelpers() {
        assertEquals("Fed-Okta", ConfigManager.providerSection("Okta"));
        assertEquals("Okta", ConfigManager.awsProviderName("Fed-Okta"));
        assertEquals("NoPrefix", ConfigManager.awsProviderName("NoPrefix"));
    }

    @Test
    void validateProviderName() {
        assertNull(configManager.validateProviderName("Corp.Okta_v2-x", null));
        assertNotNull(configManager.validateProviderName("", null));
        assertNotNull(configManager.validateProviderName("has space", null));
        assertNotNull(configManager.validateProviderName("bad]name", null));
        assertNotNull(configManager.validateProviderName("x".repeat(129), null));
        assertTrue(configManager.validateProviderName("Fed-Okta", null).contains("prefix"));
        assertTrue(configManager.validateProviderName("okta", null).contains("already exists"), "duplicates ignore case");
        assertNull(configManager.validateProviderName("Okta", "Fed-Okta"), "keeping its own name is fine");
        assertNotNull(configManager.validateProviderName("Azure", "Fed-Okta"));
    }

    @Test
    void profilesUsingProvider_countsInheritedDefault() {
        assertEquals(List.of("inherits", "own-okta"), configManager.getProfilesUsingProvider("Fed-Okta"));
        assertEquals(List.of("azure-one"), configManager.getProfilesUsingProvider("Fed-Azure"));
        assertEquals(List.of(), configManager.getProfilesUsingProvider("Fed-None"));
    }

    @Test
    void saveProvider_addsNewSection() throws Exception {
        configManager.saveProvider(null, "Ping", Map.of("loginpage", "https://sso.acme.com/idp/startSSO.ping", "logintitle", "Sign On"));

        ConfigManager reloaded = reload();
        assertTrue(reloaded.getIdpProviders().contains("Fed-Ping"));
        assertEquals("https://sso.acme.com/idp/startSSO.ping", reloaded.getProvider("Fed-Ping").get("loginpage"));
        assertFalse(Files.readString(tempHome.resolve(".aws").resolve("samlsts")).contains("\\:"), "URLs stay unescaped");
    }

    @Test
    void saveProvider_editKeepsOtherKeys() throws Exception {
        configManager.saveProvider("Fed-Okta", "Okta", Map.of("logintitle", "Acme - Sign In"));

        ConfigManager reloaded = reload();
        assertEquals("Acme - Sign In", reloaded.getProvider("Fed-Okta").get("logintitle"));
        assertEquals("keep-me", reloaded.getProvider("Fed-Okta").get("extra"));
    }

    @Test
    void saveProvider_renameUpdatesProfilesAndGlobal() throws Exception {
        configManager.saveProvider("Fed-Okta", "CorpOkta", Map.of("logintitle", "Okta"));

        ConfigManager reloaded = reload();
        assertEquals(List.of("Fed-Azure", "Fed-CorpOkta"), reloaded.getIdpProviders());
        assertEquals("keep-me", reloaded.getProvider("Fed-CorpOkta").get("extra"), "keys move with the rename");
        assertEquals("Fed-CorpOkta", reloaded.getDefaultProvider());
        assertEquals("Fed-CorpOkta", reloaded.getSamlProvider("own-okta"));
        assertEquals("Fed-CorpOkta", reloaded.getSamlProvider("inherits"));
        assertEquals("Fed-Azure", reloaded.getSamlProvider("azure-one"), "other providers' profiles are untouched");
    }

    @Test
    void deleteProvider_reassignsProfilesAndDefault() throws Exception {
        configManager.deleteProvider("Fed-Okta", "Fed-Azure");

        ConfigManager reloaded = reload();
        assertEquals(List.of("Fed-Azure"), reloaded.getIdpProviders());
        assertEquals("Fed-Azure", reloaded.getDefaultProvider());
        assertEquals("Fed-Azure", reloaded.getSamlProvider("inherits"));
        assertEquals("Fed-Azure", reloaded.getSamlProvider("own-okta"));
        assertEquals(List.of("azure-one", "inherits", "own-okta"), reloaded.getAvailableProfiles());
    }

    @Test
    void deleteProvider_canDeleteItsProfiles() throws Exception {
        configManager.deleteProvider("Fed-Okta", null);

        ConfigManager reloaded = reload();
        assertEquals(List.of("Fed-Azure"), reloaded.getIdpProviders());
        assertEquals(List.of("azure-one"), reloaded.getAvailableProfiles());
        assertNull(reloaded.getDefaultProvider(), "the default pointed at the deleted provider");
    }

    @Test
    void deleteProvider_unusedProviderLeavesEverythingElse() throws Exception {
        configManager.saveProvider(null, "Spare", Map.of("loginpage", "https://x.example/saml", "logintitle", "X"));
        configManager.deleteProvider("Fed-Spare", null);

        ConfigManager reloaded = reload();
        assertEquals(List.of("Fed-Azure", "Fed-Okta"), reloaded.getIdpProviders());
        assertEquals(3, reloaded.getAvailableProfiles().size());
        assertEquals("Fed-Okta", reloaded.getDefaultProvider());
    }

    @Test
    void setDefaultProvider_doesNotMoveExistingProfiles() throws Exception {
        configManager.setDefaultProvider("Fed-Azure");

        ConfigManager reloaded = reload();
        assertEquals("Fed-Azure", reloaded.getDefaultProvider());
        assertEquals("Fed-Okta", reloaded.getSamlProvider("inherits"), "an inheriting profile keeps the provider it used");
        assertEquals("Fed-Okta", reloaded.getSamlProvider("own-okta"));
    }
}
