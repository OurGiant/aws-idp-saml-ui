package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class SamlAuthenticatorTest {

    @Mock
    private ConfigManager configManager;

    @Mock
    private CredentialManager credentialManager;

    @Mock
    private PasswordManager passwordManager;

    private SamlAuthenticator authenticator() {
        return new SamlAuthenticator(configManager, credentialManager, passwordManager);
    }

    @Test
    void findMatchingRole_returnsArnOfMatchingAccountAndRoleName() {
        List<SamlRole> roles = List.of(
                new SamlRole("arn:aws:iam::123456789012:role/AdminRole", "arn:aws:iam::123456789012:saml-provider/Okta"),
                new SamlRole("arn:aws:iam::987654321098:role/ReadOnlyRole", "arn:aws:iam::987654321098:saml-provider/Okta")
        );

        String roleArn = authenticator().findMatchingRole(roles, "987654321098", "ReadOnlyRole");

        assertEquals("arn:aws:iam::987654321098:role/ReadOnlyRole", roleArn);
    }

    @Test
    void findMatchingRole_throwsWhenNoRoleMatchesAccountAndName() {
        List<SamlRole> roles = List.of(
                new SamlRole("arn:aws:iam::123456789012:role/AdminRole", "arn:aws:iam::123456789012:saml-provider/Okta")
        );

        RoleNotFoundException ex = assertThrows(RoleNotFoundException.class,
                () -> authenticator().findMatchingRole(roles, "999999999999", "AdminRole"));

        assertEquals("Matching role not found in SAML response: 999999999999/AdminRole"
                + " (assertion has 1 role; closest: 123456789012/AdminRole)", ex.getMessage());
        assertEquals(roles, ex.availableRoles());
    }

    @Test
    void discoverRoles_rejectsUnknownOrIncompleteProvider() {
        assertThrows(IllegalArgumentException.class,
                () -> authenticator().discoverRoles("Fed-Missing", false, false, msg -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> authenticator().discoverRoles(null, false, false, msg -> { }));
    }

    @Test
    void validateSecureLoginUrl_acceptsHttpsUrl() {
        SamlAuthenticator.validateSecureLoginUrl("https://login.example.com/app/sso/saml");
    }

    @Test
    void validateSecureLoginUrl_rejectsHttpUrl() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SamlAuthenticator.validateSecureLoginUrl("http://login.example.com/app/sso/saml"));

        assertEquals("SAML login URL must be a valid HTTPS URL without embedded credentials.", ex.getMessage());
    }
}
