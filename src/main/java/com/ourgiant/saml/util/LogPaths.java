package com.ourgiant.saml.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Where the app's own logs live — must match src/main/resources/logback.xml's FILE appender exactly. */
public final class LogPaths {

    private LogPaths() {
    }

    public static Path logsDir() {
        return Paths.get(System.getProperty("user.home"), ".aws-idp-saml-ui", "logs");
    }

    public static Path currentLogFile() {
        return logsDir().resolve("app.log");
    }

    /** Written by {@link StartupLog} directly (not via logback), so it survives a broken logging setup. */
    public static Path startupLogFile() {
        return logsDir().resolve("startup.log");
    }
}
