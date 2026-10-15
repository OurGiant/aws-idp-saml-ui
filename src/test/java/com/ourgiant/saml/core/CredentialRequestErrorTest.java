package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialRequestErrorTest {

    private static SamlRole role(String account, String name) {
        return new SamlRole("arn:aws:iam::" + account + ":role/" + name, "arn:aws:iam::" + account + ":saml-provider/Okta");
    }

    @Test
    void classify_roleNotFound_listsAvailableRolesWithClosestMatchFirst() {
        RoleNotFoundException cause = new RoleNotFoundException("111111111111", "Admin",
            List.of(role("333333333333", "Billing"), role("111111111111", "ReadOnly")));

        // SwingWorker failures arrive wrapped in an ExecutionException
        CredentialRequestError error = CredentialRequestError.classify(new ExecutionException(cause));

        assertEquals(CredentialRequestError.Category.CONFIGURATION, error.category());
        assertEquals("Role Not Found", error.title());
        assertFalse(error.retryable());
        String html = error.htmlMessage();
        assertTrue(html.contains("Roles available to you:"), html);
        assertTrue(html.indexOf("<b>111111111111 / ReadOnly</b> (same account)") < html.indexOf("333333333333 / Billing"), html);
        assertTrue(html.contains("a likely match is highlighted below"), html);
        assertTrue(error.statusMessage().contains("closest: 111111111111/ReadOnly"), error.statusMessage());
    }

    @Test
    void classify_roleNotFound_withNoNearMatchSuggestsRequestingAccess() {
        CredentialRequestError error = CredentialRequestError.classify(
            new RoleNotFoundException("111111111111", "Admin", List.of(role("333333333333", "Billing"))));

        assertTrue(error.htmlMessage().contains("ask for access to that account"), error.htmlMessage());
        assertFalse(error.htmlMessage().contains("<b>"), error.htmlMessage());
    }

    @Test
    void classify_roleNotFound_withEmptyAssertionExplainsIdpAssignment() {
        CredentialRequestError error = CredentialRequestError.classify(
            new RoleNotFoundException("111111111111", "Admin", List.of()));

        assertTrue(error.htmlMessage().contains("didn't return any AWS roles"), error.htmlMessage());
        assertFalse(error.htmlMessage().contains("<ul>"), error.htmlMessage());
    }

    @Test
    void classify_roleNotFound_explainsWhyEachNearMatchIsClose() {
        CredentialRequestError error = CredentialRequestError.classify(new RoleNotFoundException("111111111111", "Admin",
            List.of(role("111111111111", "admin"), role("222222222222", "Admin"))));

        String html = error.htmlMessage();
        assertTrue(html.contains("<b>111111111111 / admin</b> (same role, different capitalization)"), html);
        assertTrue(html.contains("<b>222222222222 / Admin</b> (same role, different account)"), html);
    }

    @Test
    void classify_roleNotFound_capsLongRoleLists() {
        List<SamlRole> roles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            roles.add(role(String.format("%012d", i + 500), "Role" + i));
        }
        CredentialRequestError error = CredentialRequestError.classify(
            new RoleNotFoundException("111111111111", "Admin", roles));

        String html = error.htmlMessage();
        assertEquals(CredentialRequestError.MAX_LISTED_ROLES + 1, html.split("<li>", -1).length - 1, html);
        assertTrue(html.contains("…and 8 more"), html);
    }

    @Test
    void classify_roleNotFound_escapesRoleNamesInHtml() {
        CredentialRequestError error = CredentialRequestError.classify(
            new RoleNotFoundException("111111111111", "Admin", List.of(role("111111111111", "<script>"))));

        assertTrue(error.htmlMessage().contains("&lt;script&gt;"), error.htmlMessage());
        assertFalse(error.htmlMessage().contains("<script>"), error.htmlMessage());
    }
}
