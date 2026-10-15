package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogViewerDialogReadTailTest {

    @Test
    void readTail_returnsWholeFileWhenSmall(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, "line one\nline two\n");

        assertEquals("line one\nline two\n", LogViewerDialog.readTail(file, 1024));
    }

    @Test
    void readTail_truncatesToLastLinesAtLineBoundary(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("app.log");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            content.append("entry-").append(i).append('\n');
        }
        Files.writeString(file, content.toString());

        String tail = LogViewerDialog.readTail(file, 100);

        assertTrue(tail.startsWith("… (showing the last"), tail);
        assertTrue(tail.endsWith("entry-199\n"), tail);
        assertFalse(tail.contains("entry-0\n"), tail);
        String afterNotice = tail.substring(tail.indexOf('\n') + 1);
        assertTrue(afterNotice.startsWith("entry-"), afterNotice);
    }
}
