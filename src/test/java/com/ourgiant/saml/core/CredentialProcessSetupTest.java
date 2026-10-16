package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CredentialProcessSetupTest {

    @Test
    void nativeLauncherIsUsedWhenRunningFromAnInstall() {
        assertEquals("\"C:\\Program Files\\AWS IDP SAML\\AWS IDP SAML.exe\"",
            CredentialProcessSetup.launchCommand("C:\\Program Files\\AWS IDP SAML\\AWS IDP SAML.exe", Path.of("/jdk"), Path.of("/x.jar"), true));
    }

    @Test
    void otherwiseJavaDashJarWithQuotedPaths() {
        // Paths print in the platform's own format (backslashes on Windows), which is what the AWS CLI there needs.
        Path javaHome = Path.of("/opt/jdk");
        Path jar = Path.of("/home/me/my apps/aws-idp-saml-ui-all.jar");
        assertEquals("\"" + javaHome.resolve("bin").resolve("java") + "\" -jar \"" + jar + "\"",
            CredentialProcessSetup.launchCommand(null, javaHome, jar, false));
        assertEquals("\"" + javaHome.resolve("bin").resolve("java.exe") + "\" -jar \"" + jar + "\"",
            CredentialProcessSetup.launchCommand(null, javaHome, jar, true));
    }

    @Test
    void snippetUsesSeparateCliProfileAndIncludesRegionWhenKnown() {
        assertEquals("[profile dev-cli]\ncredential_process = \"/app\" --credential-process dev\nregion = eu-west-1\n",
            CredentialProcessSetup.configSnippet("dev", "\"/app\"", "eu-west-1"));
        assertEquals("[profile odd name-cli]\ncredential_process = \"/app\" --credential-process \"odd name\"\n",
            CredentialProcessSetup.configSnippet("odd name", "\"/app\"", null));
    }
}
