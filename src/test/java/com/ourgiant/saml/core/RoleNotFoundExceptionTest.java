package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleNotFoundExceptionTest {

    private static SamlRole role(String account, String name) {
        return new SamlRole("arn:aws:iam::" + account + ":role/" + name, "arn:aws:iam::" + account + ":saml-provider/Okta");
    }

    @Test
    void nearMatches_ordersCaseOnlyThenSameAccountThenSameRoleName() {
        SamlRole otherAccountSameName = role("222222222222", "Admin");
        SamlRole sameAccountOther = role("111111111111", "ReadOnly");
        SamlRole caseOnly = role("111111111111", "admin");
        SamlRole unrelated = role("333333333333", "Billing");

        RoleNotFoundException ex = new RoleNotFoundException("111111111111", "Admin",
            List.of(otherAccountSameName, unrelated, sameAccountOther, caseOnly));

        assertEquals(List.of(caseOnly, sameAccountOther, otherAccountSameName), ex.nearMatches());
        assertEquals(4, ex.availableRoles().size());
        assertTrue(ex.getMessage().endsWith("(assertion has 4 roles; closest: 111111111111/admin)"), ex.getMessage());
    }

    @Test
    void message_saysWhenNothingIsInTheRequestedAccount() {
        RoleNotFoundException ex = new RoleNotFoundException("111111111111", "Admin",
            List.of(role("333333333333", "Billing")));

        assertTrue(ex.nearMatches().isEmpty());
        assertEquals("Matching role not found in SAML response: 111111111111/Admin"
            + " (assertion has 1 role, none in account 111111111111)", ex.getMessage());
    }

    @Test
    void message_saysWhenAssertionHasNoRoles() {
        RoleNotFoundException ex = new RoleNotFoundException("111111111111", "Admin", List.of());

        assertEquals("Matching role not found in SAML response: 111111111111/Admin"
            + " (the assertion contains no AWS roles)", ex.getMessage());
    }

    @Test
    void samlRoleFind_requiresExactAccountAndCaseSensitiveName() {
        SamlRole admin = role("111111111111", "Admin");
        List<SamlRole> roles = List.of(role("111111111111", "admin"), admin);

        assertEquals(admin, SamlRole.find(roles, "111111111111", "Admin"));
        assertEquals(null, SamlRole.find(roles, "222222222222", "Admin"));
    }
}
