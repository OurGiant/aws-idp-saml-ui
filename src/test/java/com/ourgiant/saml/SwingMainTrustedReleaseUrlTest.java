package com.ourgiant.saml;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingMainTrustedReleaseUrlTest {

    @Test
    void trustsAnHttpsGitHubReleaseUrl() {
        assertTrue(SwingMain.isTrustedReleaseUrl(
            URI.create("https://github.com/OurGiant/aws-idp-saml-ui/releases/tag/v1.4.9")));
    }

    @Test
    void rejectsNonHttpsScheme() {
        // Regression-style test: releaseUrl comes straight from the GitHub API response, so a
        // tampered response (only possible with an existing TLS MITM position) shouldn't be
        // able to point Desktop.browse() at an arbitrary scheme.
        assertFalse(SwingMain.isTrustedReleaseUrl(
            URI.create("file:///etc/passwd")));
    }

    @Test
    void rejectsANonGitHubHost() {
        assertFalse(SwingMain.isTrustedReleaseUrl(
            URI.create("https://evil.example.com/releases/tag/v1.4.9")));
    }

    @Test
    void rejectsAGitHubLookalikeHost() {
        assertFalse(SwingMain.isTrustedReleaseUrl(
            URI.create("https://github.com.evil.example.com/releases/tag/v1.4.9")));
    }
}
