package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CredentialsFileEditorTest {

    private static final String FILE = """
        # my credentials
        [default]
        aws_access_key_id = AKIAHANDADDED
        aws_secret_access_key = keepme

        [dev]
        aws_access_key_id = ASIADEV
        aws_secret_access_key = s
        aws_session_token = t

        ; personal account, added by hand
        [personal]
        aws_access_key_id = AKIAPERSONAL
        aws_secret_access_key = alsokeep

        [prod]
        aws_access_key_id = ASIAPROD
        aws_secret_access_key = s
        aws_session_token = t
        """;

    @Test
    void removesOnlyTheNamedSections_keepingEverythingElseVerbatim() {
        CredentialsFileEditor.Edit edit = CredentialsFileEditor.withoutSections(FILE, Set.of("dev", "prod"));

        assertEquals(Set.of("dev", "prod"), edit.removed());
        assertEquals("""
            # my credentials
            [default]
            aws_access_key_id = AKIAHANDADDED
            aws_secret_access_key = keepme

            ; personal account, added by hand
            [personal]
            aws_access_key_id = AKIAPERSONAL
            aws_secret_access_key = alsokeep
            """, edit.content());
    }

    @Test
    void missingSectionsLeaveContentIdentical() {
        CredentialsFileEditor.Edit edit = CredentialsFileEditor.withoutSections(FILE, Set.of("staging"));

        assertSame(FILE, edit.content());
        assertTrue(edit.removed().isEmpty());
    }

    @Test
    void matchesHeadersWithSpacesAndCommentsButNotPrefixes() {
        String content = "[ dev ] # temp\na = 1\n[dev-2]\nb = 2\n";

        assertEquals("[dev-2]\nb = 2\n", CredentialsFileEditor.withoutSections(content, Set.of("dev")).content());
    }

    @Test
    void preservesWindowsLineEndings() {
        assertEquals("[keep]\r\na = 1\r\n",
            CredentialsFileEditor.withoutSections("[keep]\r\na = 1\r\n\r\n[dev]\r\nb = 2\r\n", Set.of("dev")).content());
    }

    @Test
    void removingEverythingLeavesAnEmptyFile() {
        assertEquals("", CredentialsFileEditor.withoutSections("[dev]\na = 1\n", Set.of("dev")).content());
    }

    @Test
    void removeSections_writesAtomicallyKeepingPermissionsAndNeverCreatesTheFile(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("none");
        assertTrue(CredentialsFileEditor.removeSections(missing, Set.of("dev")).isEmpty());
        assertFalse(Files.exists(missing));

        Path file = dir.resolve("credentials");
        Files.writeString(file, FILE);
        boolean posix = file.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }

        assertEquals(Set.of("dev"), CredentialsFileEditor.removeSections(file, Set.of("dev", "nope")));
        String after = Files.readString(file);
        assertFalse(after.contains("ASIADEV"), after);
        assertTrue(after.contains("AKIAHANDADDED") && after.contains("AKIAPERSONAL") && after.contains("ASIAPROD"), after);
        if (posix) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
        try (var listing = Files.list(dir)) {
            assertEquals(1, listing.count(), "no temp file left behind");
        }
    }

    @Test
    void removeSections_followsSymlinks(@TempDir Path dir) throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path real = dir.resolve("real-credentials");
        Files.writeString(real, "[dev]\na = 1\n[keep]\nb = 2\n");
        Path link = Files.createSymbolicLink(dir.resolve("credentials"), real);

        CredentialsFileEditor.removeSections(link, Set.of("dev"));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals("[keep]\nb = 2\n", Files.readString(real));
    }
}
