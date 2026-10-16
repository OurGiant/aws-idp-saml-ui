package com.ourgiant.saml.core;

import com.ourgiant.saml.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Makes sure the AWS CLI/SDK config file ({@code ~/.aws/config}) has a region for each profile
 * this app writes credentials for, so {@code aws --profile <name> ...} works without a separate
 * {@code aws configure}. Without it the CLI fails with "You must specify a region".
 *
 * <p>That file is commonly hand-maintained (SSO profiles, {@code credential_process} commands,
 * comments), so this deliberately edits it as text rather than round-tripping it through an INI
 * library, which would reformat and escape everything (see #89): the only change ever made is
 * inserting one {@code region = ...} line, or appending one new section. An existing region is
 * never changed — the STS region this app uses isn't necessarily the region someone works in.
 */
public class AwsConfigFile {
    private static final Logger logger = LoggerFactory.getLogger(AwsConfigFile.class);

    /** Only plausible region ids are written, so a bad samlsts value can't inject lines into the file. */
    private static final Pattern REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");
    /** A top-level (non-indented) region key; indented lines are nested settings, e.g. under {@code s3 =}. */
    private static final Pattern REGION_KEY = Pattern.compile("^region\\s*=.*");
    private static final Pattern ANY_SECTION = Pattern.compile("^\\s*\\[.*");

    private final Path file;

    public AwsConfigFile(Path file) {
        this.file = file;
    }

    /** The file the AWS CLI itself uses: {@code AWS_CONFIG_FILE} if set, else {@code ~/.aws/config}. */
    public static AwsConfigFile forCurrentUser() {
        String override = System.getenv("AWS_CONFIG_FILE");
        if (override != null && !override.isBlank()) {
            return new AwsConfigFile(Paths.get(override));
        }
        return new AwsConfigFile(Paths.get(System.getProperty("user.home"), ".aws", "config"));
    }

    public Path getFile() {
        return file;
    }

    /**
     * Adds {@code region} to the profile's section if it has none (creating the section, and the
     * file, if needed). Returns true if the file was changed.
     */
    public boolean ensureRegion(String profileName, String region) throws IOException {
        if (region == null || !REGION.matcher(region).matches()) {
            logger.warn("Not writing region \"{}\" for profile {} to {}: not a valid AWS region id", region, profileName, file);
            return false;
        }
        // Follow a symlinked config (common with dotfile managers) so the link itself is kept.
        Path target = Files.exists(file) ? file.toRealPath() : file;
        String original = Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
        String updated = withRegion(original, profileName, region);
        if (updated.equals(original)) {
            return false;
        }
        AtomicFiles.writeString(file, updated);
        logger.info("Added region {} for profile {} to {}", region, profileName, target);
        return true;
    }

    /**
     * Pure text transform behind {@link #ensureRegion}: {@code content} unchanged if the profile's
     * section already has a region, otherwise with a region line inserted right after the section
     * header, or a new section appended. Line endings follow the existing content.
     */
    static String withRegion(String content, String profileName, String region) {
        String nl = content.contains("\r\n") ? "\r\n" : content.isEmpty() ? System.lineSeparator() : "\n";
        List<String> lines = new ArrayList<>(List.of(content.split("\r?\n", -1)));
        boolean endsWithNewline = content.isEmpty() || content.endsWith("\n");
        if (endsWithNewline && !lines.isEmpty()) {
            lines.remove(lines.size() - 1); // the empty string after the final newline
        }

        Pattern header = sectionHeader(profileName);
        for (int i = 0; i < lines.size(); i++) {
            if (!header.matcher(lines.get(i)).matches()) {
                continue;
            }
            for (int j = i + 1; j < lines.size() && !ANY_SECTION.matcher(lines.get(j)).matches(); j++) {
                if (REGION_KEY.matcher(lines.get(j)).matches()) {
                    return content;
                }
            }
            lines.add(i + 1, "region = " + region);
            return String.join(nl, lines) + (endsWithNewline ? nl : "");
        }

        StringBuilder result = new StringBuilder(content);
        if (!content.isEmpty()) {
            if (!endsWithNewline) {
                result.append(nl);
            }
            result.append(nl); // blank line between sections
        }
        result.append("[").append(sectionName(profileName)).append("]").append(nl)
            .append("region = ").append(region).append(nl);
        return result.toString();
    }

    /** The CLI reads {@code [default]} for the default profile and {@code [profile name]} for every other. */
    private static String sectionName(String profileName) {
        return "default".equals(profileName) ? "default" : "profile " + profileName;
    }

    /** Matches the profile's header the way the CLI does: extra spaces allowed, optional trailing comment. */
    private static Pattern sectionHeader(String profileName) {
        String name = Pattern.quote(profileName);
        String body = "default".equals(profileName)
            ? "(?:default|profile\\s+default)"
            : "profile\\s+" + name;
        return Pattern.compile("^\\s*\\[\\s*" + body + "\\s*]\\s*(?:[#;].*)?$");
    }

}
