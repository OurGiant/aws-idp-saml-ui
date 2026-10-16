package com.ourgiant.saml.core;

import org.ini4j.Ini;
import org.ini4j.Profile;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * {@code --credential-process <profile>} (#222): prints a profile's saved credentials in the
 * AWS {@code credential_process} format, so the AWS CLI and SDKs can get them from this app via
 * {@code credential_process = "<app>" --credential-process <profile>} in {@code ~/.aws/config}.
 *
 * <p>Strictly read-only and quick: it reads {@code ~/.aws/credentials} and the expiry recorded in
 * the app's database (opened read-only, never created), works while the GUI is running, and never
 * starts Swing. It deliberately avoids SLF4J/logback so nothing but the JSON reaches stdout.
 * Problems go to stderr with a non-zero exit code, which the AWS CLI shows to the user.
 */
public final class CredentialProcess {

    public static final String FLAG = "--credential-process";
    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;
    /** Credentials this close to expiry are treated as expired, so a command doesn't fail part-way through. */
    static final Duration MIN_REMAINING = Duration.ofSeconds(60);
    private static final String APP = "AWS IdP SAML UI";

    private CredentialProcess() {
    }

    /** @param home the user's home directory (normally {@code user.home}) */
    public static int run(String[] args, Path home, Instant now, PrintStream out, PrintStream err) {
        if (args.length != 2 || !FLAG.equals(args[0]) || args[1].isBlank()) {
            err.println("Usage: " + FLAG + " <profile>");
            return USAGE;
        }
        String profile = args[1].trim();
        try {
            Path credentialsFile = home.resolve(".aws").resolve("credentials");
            if (!Files.exists(credentialsFile)) {
                return fail(err, "No credentials file at " + credentialsFile + ". Request credentials for '" + profile + "' in " + APP + ".");
            }
            Profile.Section section = new Ini(credentialsFile.toFile()).get(profile);
            String keyId = section != null ? section.get("aws_access_key_id") : null;
            String secret = section != null ? section.get("aws_secret_access_key") : null;
            if (isBlank(keyId) || isBlank(secret)) {
                return fail(err, "No saved credentials for profile '" + profile + "'. Request them in " + APP + ".");
            }
            Instant expiration = readExpiration(home.resolve(".aws").resolve("aws_saml.db"), profile);
            if (expiration == null) {
                return fail(err, "The expiry of profile '" + profile + "' isn't known. Request its credentials again in " + APP + ".");
            }
            if (!expiration.isAfter(now.plus(MIN_REMAINING))) {
                return fail(err, "Credentials for profile '" + profile + "' expired at " + expiration.truncatedTo(ChronoUnit.SECONDS)
                    + (expiration.isAfter(now) ? " (less than a minute left)" : "") + ". Refresh them in " + APP + ".");
            }
            out.println(toJson(keyId.trim(), secret.trim(), section.get("aws_session_token"), expiration));
            out.flush();
            return OK;
        } catch (Exception e) {
            return fail(err, "Couldn't read credentials for profile '" + profile + "': " + e.getMessage());
        }
    }

    private static int fail(PrintStream err, String message) {
        err.println("aws-idp-saml-ui: " + message);
        return FAILED;
    }

    /** The credential_process JSON (Version 1). {@code Expiration} is ISO-8601 UTC, whole seconds. */
    static String toJson(String keyId, String secret, String sessionToken, Instant expiration) {
        StringBuilder json = new StringBuilder("{\"Version\": 1")
            .append(", \"AccessKeyId\": \"").append(escape(keyId)).append('"')
            .append(", \"SecretAccessKey\": \"").append(escape(secret)).append('"');
        if (!isBlank(sessionToken)) {
            json.append(", \"SessionToken\": \"").append(escape(sessionToken.trim())).append('"');
        }
        return json.append(", \"Expiration\": \"").append(expiration.truncatedTo(ChronoUnit.SECONDS)).append("\"}").toString();
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** The recorded expiry, or null. The database is opened read-only and never created. */
    static Instant readExpiration(Path database, String profile) throws Exception {
        if (!Files.exists(database)) {
            return null;
        }
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true); // opens SQLITE_OPEN_READONLY: never writes, never creates
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath(), config.toProperties());
             PreparedStatement statement = connection.prepareStatement("SELECT expiration FROM token_state WHERE profile_name = ?")) {
            statement.setString(1, profile);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Instant.parse(rs.getString(1)) : null;
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
