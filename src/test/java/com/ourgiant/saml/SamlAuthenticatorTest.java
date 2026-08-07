package com.ourgiant.saml;

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

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authenticator().findMatchingRole(roles, "999999999999", "AdminRole"));

        assertEquals("Matching role not found in SAML response: 999999999999/AdminRole", ex.getMessage());
    }
}
