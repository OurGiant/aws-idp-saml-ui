package com.ourgiant.saml.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Records each launch attempt and how it ended (started, cancelled, or failed with a stack trace)
 * in its own small file. Deliberately plain java.nio rather than logback: a launch failure can be
 * caused by the logging setup itself, and this file needs to capture that case too. Never throws —
 * failing to write the startup log must not be what breaks startup.
 */
public final class StartupLog {
    /** Once the file passes this size it is rotated to {@code startup.log.1} (replacing any previous one). */
    static final long MAX_BYTES = 256 * 1024;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path file;
    private volatile boolean finished;

    public StartupLog(Path file) {
        this.file = file;
    }

    public static StartupLog forCurrentUser() {
        return new StartupLog(LogPaths.startupLogFile());
    }

    public Path getFile() {
        return file;
    }

    /** True once {@link #succeeded}, {@link #cancelled} or {@link #failed} has been recorded. */
    public boolean isFinished() {
        return finished;
    }

    public void begin(String version) {
        rotateIfTooLarge();
        write("Launch started: version=" + version
            + ", java=" + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"
            + ", os=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
            + " " + System.getProperty("os.arch"));
    }

    public void succeeded() {
        finish("Startup completed");
    }

    public void cancelled(String reason) {
        finish("Startup cancelled: " + reason);
    }

    public void failed(String phase, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        finish("Startup FAILED during " + phase + ": " + error + System.lineSeparator() + trace);
    }

    private void finish(String message) {
        finished = true;
        write(message);
    }

    private void rotateIfTooLarge() {
        try {
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not rotate startup log " + file + ": " + e);
        }
    }

    private synchronized void write(String message) {
        String line = LocalDateTime.now().format(TIMESTAMP) + " " + message + System.lineSeparator();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not write startup log " + file + ": " + e);
            System.err.print(line);
        }
    }
}
