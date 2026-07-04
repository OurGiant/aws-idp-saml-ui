package com.ourgiant.saml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Restricts sensitive files (credentials, encrypted-password database) to owner-only access,
 * matching the AWS CLI's handling of ~/.aws/credentials.
 */
final class FilePermissions {
    private static final Logger logger = LoggerFactory.getLogger(FilePermissions.class);

    private FilePermissions() {
    }

    static void restrictToOwner(Path path) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            } else {
                File file = path.toFile();
                file.setReadable(false, false);
                file.setWritable(false, false);
                file.setReadable(true, true);
                file.setWritable(true, true);
            }
        } catch (IOException | UnsupportedOperationException e) {
            logger.warn("Could not restrict permissions on file: {}", path, e);
        }
    }
}
