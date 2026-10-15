package com.ourgiant.saml.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupLogTest {

    @Test
    void begin_createsMissingDirectoriesAndRecordsEnvironment(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("nested/logs/startup.log");
        StartupLog log = new StartupLog(file);

        log.begin("9.9.9");

        String text = Files.readString(file);
        assertTrue(text.contains("Launch started: version=9.9.9"), text);
        assertTrue(text.contains("java=" + System.getProperty("java.version")), text);
        assertFalse(log.isFinished());
    }

    @Test
    void failed_appendsPhaseAndStackTraceAndMarksFinished(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("startup.log");
        StartupLog log = new StartupLog(file);
        log.begin("1.0.0");

        log.failed("main window initialization", new IllegalStateException("boom"));

        String text = Files.readString(file);
        assertTrue(text.contains("Startup FAILED during main window initialization: java.lang.IllegalStateException: boom"), text);
        assertTrue(text.contains("at com.ourgiant.saml.util.StartupLogTest"), text);
        assertTrue(log.isFinished());
    }

    @Test
    void succeededAndCancelled_appendAcrossLaunches(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("startup.log");
        new StartupLog(file).begin("1.0.0");
        new StartupLog(file).succeeded();
        StartupLog second = new StartupLog(file);
        second.begin("1.0.0");
        second.cancelled("first-run setup was not completed");

        String text = Files.readString(file);
        assertEquals(2, text.split("Launch started", -1).length - 1, text);
        assertTrue(text.contains("Startup completed"), text);
        assertTrue(text.contains("Startup cancelled: first-run setup was not completed"), text);
        assertTrue(second.isFinished());
    }

    @Test
    void begin_rotatesOversizedFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("startup.log");
        Files.writeString(file, "x".repeat((int) StartupLog.MAX_BYTES + 1));

        new StartupLog(file).begin("1.0.0");

        Path rotated = tempDir.resolve("startup.log.1");
        assertTrue(Files.exists(rotated));
        assertEquals(StartupLog.MAX_BYTES + 1, Files.size(rotated));
        assertTrue(Files.size(file) < 1024);
    }

    @Test
    void write_neverThrowsWhenFileCannotBeWritten(@TempDir Path tempDir) throws Exception {
        Path blocker = tempDir.resolve("not-a-dir");
        Files.writeString(blocker, "");
        StartupLog log = new StartupLog(blocker.resolve("startup.log"));

        log.begin("1.0.0");
        log.failed("launch", new RuntimeException("x"));

        assertTrue(log.isFinished());
    }
}
