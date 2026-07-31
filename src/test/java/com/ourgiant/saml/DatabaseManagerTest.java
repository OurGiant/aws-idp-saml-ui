package com.ourgiant.saml;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DatabaseManagerTest {

    @TempDir
    Path tempHome;

    private String originalUserHome;
    private DatabaseManager databaseManager;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        databaseManager = new DatabaseManager();
    }

    @AfterEach
    void tearDown() {
        databaseManager.close();
        System.setProperty("user.home", originalUserHome);
    }

    private void backdateMissingSince(String profileName, Instant when) throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("connection");
        field.setAccessible(true);
        Connection connection = (Connection) field.get(databaseManager);
        try (PreparedStatement pstmt = connection.prepareStatement(
                "UPDATE token_state SET missing_since = ? WHERE profile_name = ?")) {
            pstmt.setString(1, when.toString());
            pstmt.setString(2, profileName);
            pstmt.executeUpdate();
        }
    }

    private String readMissingSince(String profileName) throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("connection");
        field.setAccessible(true);
        Connection connection = (Connection) field.get(databaseManager);
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT missing_since FROM token_state WHERE profile_name = ?")) {
            pstmt.setString(1, profileName);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getString("missing_since") : null;
            }
        }
    }

    @Test
    void deleteProfileState_removesRow() {
        databaseManager.updateExpiration("foo", Instant.now().plusSeconds(3600));
        assertNotNull(databaseManager.getExpiration("foo"));

        databaseManager.deleteProfileState("foo");

        assertNull(databaseManager.getExpiration("foo"));
    }

    @Test
    void reconcileProfiles_forceImmediate_prunesProfilesNotInCurrentSet() {
        databaseManager.updateExpiration("keep", Instant.now().plusSeconds(3600));
        databaseManager.updateExpiration("stale", Instant.now().plusSeconds(3600));

        int pruned = databaseManager.reconcileProfiles(Set.of("keep"), true);

        assertEquals(1, pruned);
        assertNotNull(databaseManager.getExpiration("keep"));
        assertNull(databaseManager.getExpiration("stale"));
    }

    @Test
    void reconcileProfiles_withoutForce_doesNotPruneWithinGracePeriod() {
        databaseManager.updateExpiration("stale", Instant.now().plusSeconds(3600));

        int pruned = databaseManager.reconcileProfiles(Set.of(), false);

        assertEquals(0, pruned, "a profile missing for the first time should be marked, not pruned");
        assertNotNull(databaseManager.getExpiration("stale"));
    }

    @Test
    void reconcileProfiles_withoutForce_prunesOnceGracePeriodElapses() throws Exception {
        databaseManager.updateExpiration("stale", Instant.now().plusSeconds(3600));
        databaseManager.reconcileProfiles(Set.of(), false); // marks missing_since = now

        backdateMissingSince("stale", Instant.now().minus(java.time.Duration.ofDays(8)));

        int pruned = databaseManager.reconcileProfiles(Set.of(), false);

        assertEquals(1, pruned);
        assertNull(databaseManager.getExpiration("stale"));
    }

    @Test
    void reconcileProfiles_clearsMissingMarkerWhenProfileReappears() throws Exception {
        databaseManager.updateExpiration("flaky", Instant.now().plusSeconds(3600));
        databaseManager.reconcileProfiles(Set.of(), false); // marks missing
        assertNotNull(readMissingSince("flaky"));

        databaseManager.reconcileProfiles(Set.of("flaky"), false); // reappears

        assertNull(readMissingSince("flaky"));
        assertNotNull(databaseManager.getExpiration("flaky"));
    }

    @Test
    void createTables_addsMissingSinceColumnToPreexistingTokenStateTable() throws Exception {
        // Simulate an on-disk DB created before the missing_since column existed.
        Field field = DatabaseManager.class.getDeclaredField("connection");
        field.setAccessible(true);
        Connection connection = (Connection) field.get(databaseManager);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE token_state DROP COLUMN missing_since");
        }

        // Re-running the migration path should graft the column back on without error.
        java.lang.reflect.Method method = DatabaseManager.class.getDeclaredMethod("createTables");
        method.setAccessible(true);
        method.invoke(databaseManager);

        databaseManager.updateExpiration("foo", Instant.now().plusSeconds(3600));
        databaseManager.reconcileProfiles(Set.of(), false);
        assertNotNull(readMissingSince("foo"));
    }
}
