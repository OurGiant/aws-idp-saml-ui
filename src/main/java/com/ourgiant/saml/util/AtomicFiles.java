package com.ourgiant.saml.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Rewrites a user-owned text file (e.g. {@code ~/.aws/config}, {@code ~/.aws/credentials}) without
 * ever leaving it half-written: the new content goes to a temp file in the same directory, which
 * then replaces the target in one move. Symlinks are followed (dotfile managers often link these
 * files), an existing file's POSIX permissions are kept, and a newly created file is owner-only.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void writeString(Path file, String content) throws IOException {
        boolean isNew = !Files.exists(file);
        Path target = isNew ? file : file.toRealPath();
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            if (!isNew) {
                try {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target));
                } catch (UnsupportedOperationException | IOException e) {
                    // Non-POSIX filesystem: the moved file gets default permissions, same as a fresh write.
                }
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        if (isNew) {
            FilePermissions.restrictToOwner(target);
        }
    }
}
