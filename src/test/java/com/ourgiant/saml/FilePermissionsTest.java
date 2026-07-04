package com.ourgiant.saml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FilePermissionsTest {

    @Test
    void restrictToOwner_setsOwnerOnlyReadWriteOnPosix(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("sensitive.db");
        Files.writeString(file, "secret");
        assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"));

        FilePermissions.restrictToOwner(file);

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
    }
}
