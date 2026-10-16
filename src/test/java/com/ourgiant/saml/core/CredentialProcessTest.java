package com.ourgiant.saml.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialProcessTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @TempDir
    Path home;
    private String originalUserHome;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws Exception {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".aws"));
        Files.writeString(home.resolve(".aws/credentials"),
            "[dev]\naws_access_key_id = ASIADEV\naws_secret_access_key = abc/def+ghi=\naws_session_token = IQo+token/x==\n\n"
                + "[old]\naws_access_key_id = ASIAOLD\naws_secret_access_key = s\naws_session_token = t\n\n"
                + "[soon]\naws_access_key_id = ASIASOON\naws_secret_access_key = s\naws_session_token = t\n\n"
                + "[nodate]\naws_access_key_id = ASIANODATE\naws_secret_access_key = s\naws_session_token = t\n");
        DatabaseManager db = new DatabaseManager();
        db.updateExpiration("dev", NOW.plus(Duration.ofHours(2)).plusMillis(250));
        db.updateExpiration("old", NOW.minus(Duration.ofMinutes(5)));
        db.updateExpiration("soon", NOW.plusSeconds(30));
        db.close(); // Windows can't delete @TempDir files that are still open
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalUserHome);
    }

    private int run(String... args) {
        return CredentialProcess.run(args, home, NOW, new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void printsCredentialProcessJsonForValidCredentials_andNothingElse() {
        assertEquals(0, run("--credential-process", "dev"));

        assertEquals("{\"Version\": 1, \"AccessKeyId\": \"ASIADEV\", \"SecretAccessKey\": \"abc/def+ghi=\", "
            + "\"SessionToken\": \"IQo+token/x==\", \"Expiration\": \"2026-10-15T14:00:00Z\"}" + System.lineSeparator(),
            out.toString(StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void expiredOrNearlyExpiredCredentialsFailWithAMessageOnStderr() {
        assertEquals(1, run("--credential-process", "old"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("expired at 2026-10-15T11:55:00Z"), err.toString());
        err.reset();

        assertEquals(1, run("--credential-process", "soon"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("less than a minute left"), err.toString());
        assertEquals("", out.toString(StandardCharsets.UTF_8), "nothing on stdout when failing");
    }

    @Test
    void unknownProfileOrUnknownExpiryFail() {
        assertEquals(1, run("--credential-process", "nope"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("No saved credentials for profile 'nope'"), err.toString());
        err.reset();
        assertEquals(1, run("--credential-process", "nodate"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("expiry of profile 'nodate' isn't known"), err.toString());
    }

    @Test
    void badArgumentsAreAUsageError() {
        assertEquals(2, run("--credential-process"));
        assertEquals(2, run("--credential-process", "a", "b"));
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("Usage: --credential-process <profile>"));
    }

    @Test
    void neverWritesTheDatabaseOrCreatesOne() throws Exception {
        Path db = home.resolve(".aws/aws_saml.db");
        byte[] before = Files.readAllBytes(db);
        run("--credential-process", "dev");
        assertArrayEquals(before, Files.readAllBytes(db));
        try (var files = Files.list(home.resolve(".aws"))) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("aws_saml.db-")), "no journal/WAL files");
        }

        Files.delete(db);
        assertEquals(1, run("--credential-process", "dev"));
        assertFalse(Files.exists(db), "a missing database is not created");
    }

    @Test
    void escapesJsonSpecialCharacters() {
        assertEquals("a\\\"b\\\\c\\nd\\u0001", CredentialProcess.escape("a\"b\\c\nd\u0001"));
        String json = CredentialProcess.toJson("K", "S", null, NOW);
        assertFalse(json.contains("SessionToken"), json);
        assertTrue(Arrays.asList(json.split(", ")).contains("\"Expiration\": \"2026-10-15T12:00:00Z\"}"), json);
    }
}
