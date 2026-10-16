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
        assertEquals("\"/opt/jdk/bin/java\" -jar \"/home/me/my apps/aws-idp-saml-ui-all.jar\"",
            CredentialProcessSetup.launchCommand(null, Path.of("/opt/jdk"), Path.of("/home/me/my apps/aws-idp-saml-ui-all.jar"), false));
    }

    @Test
    void snippetUsesSeparateCliProfileAndIncludesRegionWhenKnown() {
        assertEquals("[profile dev-cli]\ncredential_process = \"/app\" --credential-process dev\nregion = eu-west-1\n",
            CredentialProcessSetup.configSnippet("dev", "\"/app\"", "eu-west-1"));
        assertEquals("[profile odd name-cli]\ncredential_process = \"/app\" --credential-process \"odd name\"\n",
            CredentialProcessSetup.configSnippet("odd name", "\"/app\"", null));
    }
}
