package com.ourgiant.saml.core;

import java.nio.file.Path;

/**
 * Builds the {@code ~/.aws/config} snippet that points the AWS CLI/SDKs at this app's
 * {@code --credential-process} mode (#222), with the right launch command for this install.
 */
public final class CredentialProcessSetup {

    /** Suffix for the CLI-side profile name: see {@link #cliProfileName}. */
    static final String CLI_SUFFIX = "-cli";

    private CredentialProcessSetup() {
    }

    /**
     * A separate name on purpose: for one profile name, the AWS CLI uses static keys from
     * {@code ~/.aws/credentials} before {@code credential_process}, and this app writes the
     * profile's keys there, so a same-named config profile would never call the app.
     */
    public static String cliProfileName(String profile) {
        return profile + CLI_SUFFIX;
    }

    /**
     * How to start this app from a credential_process line: the native launcher when running
     * from an installer build (jpackage sets {@code jpackage.app-path}), else {@code java -jar}.
     */
    public static String launchCommand(String jpackageAppPath, Path javaHome, Path jarPath, boolean windows) {
        if (jpackageAppPath != null && !jpackageAppPath.isBlank()) {
            return quote(jpackageAppPath);
        }
        Path java = javaHome.resolve("bin").resolve(windows ? "java.exe" : "java");
        return quote(java.toString()) + " -jar " + quote(jarPath.toString());
    }

    public static String configSnippet(String profile, String launchCommand, String region) {
        StringBuilder snippet = new StringBuilder("[profile ").append(cliProfileName(profile)).append("]\n")
            .append("credential_process = ").append(launchCommand).append(' ').append(CredentialProcess.FLAG)
            .append(' ').append(profile.matches("[A-Za-z0-9._-]+") ? profile : quote(profile)).append('\n');
        if (region != null && !region.isBlank()) {
            snippet.append("region = ").append(region.trim()).append('\n');
        }
        return snippet.toString();
    }

    /** Double quotes work for both botocore's POSIX and Windows command splitting. */
    static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
