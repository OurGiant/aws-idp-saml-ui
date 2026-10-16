package com.ourgiant.saml.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialManagerClearTest {

    @TempDir
    Path tempHome;
    private String originalUserHome;
    private CredentialManager manager;

    @BeforeEach
    void setUp() throws Exception {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".aws"));
        Files.writeString(tempHome.resolve(".aws/credentials"),
            "[dev]\naws_access_key_id = ASIADEV\naws_secret_access_key = s\naws_session_token = t\n\n"
                + "[handmade]\naws_access_key_id = AKIAKEEP\naws_secret_access_key = k\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        // CredentialManager's TokenStateManager keeps its own SQLite connection open; close it so @TempDir
        // can delete the database (Windows refuses to delete open files, as in ConfigManagerTest).
        if (manager != null) {
            java.lang.reflect.Field field = CredentialManager.class.getDeclaredField("tokenStateManager");
            field.setAccessible(true);
            ((TokenStateManager) field.get(manager)).close();
        }
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void clearCredentials_removesSectionAndExpiry_keepingOtherEntries() throws Exception {
        DatabaseManager db = new DatabaseManager();
        db.updateExpiration("dev", Instant.now().plusSeconds(3600));
        manager = new CredentialManager();
        assertNotNull(manager.getCredentials("dev"));

        Set<String> removed = manager.clearCredentials(Set.of("dev", "never-had-any"));

        assertEquals(Set.of("dev"), removed);
        assertNull(manager.getCredentials("dev"));
        assertNotNull(manager.getCredentials("handmade"));
        assertNull(db.getExpiration("dev"));
        assertTrue(Files.readString(tempHome.resolve(".aws/credentials")).contains("AKIAKEEP"));
        db.close();
    }
}
