package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AwsConfigFileTest {

    // ---- withRegion: pure text transform ----

    @Test
    void appendsNewSectionToEmptyContent() {
        String nl = System.lineSeparator();
        assertEquals("[profile dev]" + nl + "region = us-west-2" + nl,
            AwsConfigFile.withRegion("", "dev", "us-west-2"));
    }

    @Test
    void appendsNewSectionAfterExistingOnesWithBlankLine() {
        String content = "[default]\nregion = us-east-1\n";

        assertEquals("[default]\nregion = us-east-1\n\n[profile dev]\nregion = us-west-2\n",
            AwsConfigFile.withRegion(content, "dev", "us-west-2"));
    }

    @Test
    void appendsWhenFileHasNoTrailingNewline() {
        assertEquals("[default]\noutput = json\n\n[profile dev]\nregion = eu-west-1\n",
            AwsConfigFile.withRegion("[default]\noutput = json", "dev", "eu-west-1"));
    }

    @Test
    void insertsRegionIntoExistingSectionWithoutOne_leavingEverythingElseUntouched() {
        String content = "# my config\n"
            + "[profile dev]\n"
            + "output = json\n"
            + "credential_process = \"C:\\\\tools\\\\get-creds.exe\" --profile dev\n"
            + "\n"
            + "[profile other]\n"
            + "region = ap-south-1\n";

        assertEquals("# my config\n"
                + "[profile dev]\n"
                + "region = us-west-2\n"
                + "output = json\n"
                + "credential_process = \"C:\\\\tools\\\\get-creds.exe\" --profile dev\n"
                + "\n"
                + "[profile other]\n"
                + "region = ap-south-1\n",
            AwsConfigFile.withRegion(content, "dev", "us-west-2"));
    }

    @Test
    void neverChangesAnExistingRegion() {
        String content = "[profile dev]\nregion=eu-central-1\n";

        assertSame(content, AwsConfigFile.withRegion(content, "dev", "us-west-2"));
    }

    @Test
    void ignoresRegionInOtherSectionsAndNestedIndentedRegion() {
        String content = "[profile dev]\ns3 =\n  region = eu-west-1\n[profile prod]\nregion = us-east-1\n";

        assertEquals("[profile dev]\nregion = us-west-2\ns3 =\n  region = eu-west-1\n[profile prod]\nregion = us-east-1\n",
            AwsConfigFile.withRegion(content, "dev", "us-west-2"));
    }

    @Test
    void matchesHeadersWithExtraSpacesAndTrailingComments() {
        String content = "[  profile   dev  ]  # work account\noutput = json\n";

        assertEquals("[  profile   dev  ]  # work account\nregion = us-west-2\noutput = json\n",
            AwsConfigFile.withRegion(content, "dev", "us-west-2"));
    }

    @Test
    void doesNotTreatBareNamedSectionOrPrefixMatchAsTheProfile() {
        // The CLI ignores [dev] (non-default profiles need the "profile " prefix), and [profile dev-2] is a different profile.
        String content = "[dev]\noutput = json\n[profile dev-2]\noutput = text\n";

        assertEquals(content + "\n[profile dev]\nregion = us-west-2\n",
            AwsConfigFile.withRegion(content, "dev", "us-west-2"));
    }

    @Test
    void profileNamesWithRegexCharactersAreMatchedLiterally() {
        String content = "[profile a.b+c]\noutput = json\n";

        assertEquals("[profile a.b+c]\nregion = us-west-2\noutput = json\n",
            AwsConfigFile.withRegion(content, "a.b+c", "us-west-2"));
    }

    @Test
    void defaultProfileUsesBareDefaultSectionAndAcceptsProfileDefault() {
        assertEquals("[default]\nregion = us-west-2\n", AwsConfigFile.withRegion("[default]\n", "default", "us-west-2"));
        assertEquals("[profile default]\nregion = us-west-2\n", AwsConfigFile.withRegion("[profile default]\n", "default", "us-west-2"));
        assertTrue(AwsConfigFile.withRegion("[profile x]\n", "default", "us-west-2").endsWith("[default]\nregion = us-west-2\n"));
    }

    @Test
    void preservesWindowsLineEndings() {
        assertEquals("[profile dev]\r\nregion = us-west-2\r\noutput = json\r\n",
            AwsConfigFile.withRegion("[profile dev]\r\noutput = json\r\n", "dev", "us-west-2"));
        assertEquals("[default]\r\n\r\n[profile dev]\r\nregion = us-west-2\r\n",
            AwsConfigFile.withRegion("[default]\r\n", "dev", "us-west-2"));
    }

    // ---- ensureRegion: file handling ----

    @Test
    void ensureRegion_createsFileAndDirectoryOwnerOnly(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".aws/config");

        assertTrue(new AwsConfigFile(file).ensureRegion("dev", "us-west-2"));

        assertTrue(Files.readString(file).contains("[profile dev]"));
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
        }
    }

    @Test
    void ensureRegion_isNoOpWhenRegionPresent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config");
        Files.writeString(file, "[profile dev]\nregion = eu-west-1\n");
        var before = Files.getLastModifiedTime(file);

        assertFalse(new AwsConfigFile(file).ensureRegion("dev", "us-west-2"));
        assertEquals(before, Files.getLastModifiedTime(file));
    }

    @Test
    void ensureRegion_keepsExistingPermissionsAndSymlink(@TempDir Path dir) throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path real = dir.resolve("dotfiles/aws-config");
        Files.createDirectories(real.getParent());
        Files.writeString(real, "[profile dev]\noutput = json\n");
        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rw-r--r--"));
        Path link = Files.createSymbolicLink(dir.resolve("config"), real);

        assertTrue(new AwsConfigFile(link).ensureRegion("dev", "us-west-2"));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals("[profile dev]\nregion = us-west-2\noutput = json\n", Files.readString(real));
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(real));
        try (var stream = Files.list(real.getParent())) {
            assertEquals(1, stream.count(), "temp file left behind");
        }
    }

    @Test
    void ensureRegion_refusesValuesThatAreNotRegionIds(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config");
        AwsConfigFile config = new AwsConfigFile(file);

        assertFalse(config.ensureRegion("dev", "us-west-2\n[profile evil]"));
        assertFalse(config.ensureRegion("dev", ""));
        assertFalse(config.ensureRegion("dev", null));
        assertFalse(Files.exists(file));

        assertTrue(config.ensureRegion("gov", "us-gov-west-1"));
    }

    @Test
    void samlAuthenticator_swallowsConfigWriteFailures(@TempDir Path dir) throws Exception {
        Path notWritable = dir.resolve("config");
        Files.createDirectories(notWritable); // a directory where the file should be

        new SamlAuthenticator(null, null, null, new AwsConfigFile(notWritable)).ensureRegionInAwsConfig("dev", "us-west-2");
        new SamlAuthenticator(null, null, null, null).ensureRegionInAwsConfig("dev", "us-west-2");
    }
}
