package com.ourgiant.saml.core;

import com.ourgiant.saml.core.RoleImportPlanner.Candidate;
import com.ourgiant.saml.core.RoleImportPlanner.Choice;
import com.ourgiant.saml.core.RoleImportPlanner.ExistingProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleImportPlannerTest {

    private static SamlRole role(String account, String name, String awsProvider) {
        return new SamlRole("arn:aws:iam::" + account + ":role/" + name, "arn:aws:iam::" + account + ":saml-provider/" + awsProvider);
    }

    @Test
    void plan_sortsDedupesAndSuggestsNames() {
        List<Candidate> plan = RoleImportPlanner.plan(List.of(
            role("222222222222", "ReadOnly", "Okta"),
            role("111111111111", "Admin", "Okta"),
            role("222222222222", "ReadOnly", "Okta")), "Fed-Okta", Map.of());

        assertEquals(2, plan.size());
        assertEquals("111111111111-admin", plan.get(0).suggestedProfileName());
        assertEquals("Admin (111111111111)", plan.get(0).suggestedGuiName());
        assertEquals("222222222222-readonly", plan.get(1).suggestedProfileName());
        assertTrue(plan.get(0).importable());
    }

    @Test
    void plan_marksRolesThatAlreadyHaveAProfile() {
        List<Candidate> plan = RoleImportPlanner.plan(List.of(role("111111111111", "Admin", "Okta")), "Fed-Okta",
            Map.of("prod-admin", new ExistingProfile("111111111111", "Admin")));

        assertEquals("prod-admin", plan.get(0).existingProfile());
        assertFalse(plan.get(0).importable());
        assertNull(plan.get(0).suggestedProfileName());
    }

    @Test
    void plan_flagsAwsProviderNameThatDoesNotMatchTheFedSection() {
        List<Candidate> plan = RoleImportPlanner.plan(List.of(role("111111111111", "Admin", "OktaProd")), "Fed-Okta", Map.of());

        assertEquals("OktaProd", plan.get(0).awsProviderMismatch());
        assertFalse(plan.get(0).importable());
    }

    @Test
    void plan_makesSuggestedNamesUniqueAgainstExistingAndEachOther() {
        List<Candidate> plan = RoleImportPlanner.plan(List.of(
                role("111111111111", "Dev Ops", "Okta"),
                role("111111111111", "Dev+Ops", "Okta")), "Fed-Okta",
            Map.of("111111111111-dev-ops", new ExistingProfile("999999999999", "Other")));

        assertEquals("111111111111-dev-ops-2", plan.get(0).suggestedProfileName());
        assertEquals("111111111111-dev-ops-3", plan.get(1).suggestedProfileName());
    }

    @Test
    void sanitize_keepsOnlyCliSafeCharacters() {
        assertEquals("123-my-role", RoleImportPlanner.sanitize("123 / My Role!"));
        assertEquals("a.b_c-d", RoleImportPlanner.sanitize("A.b_c-D"));
        assertEquals("profile", RoleImportPlanner.sanitize("///"));
    }

    @Test
    void awsProviderName_extractsNameFromPrincipalArn() {
        assertEquals("Okta", RoleImportPlanner.awsProviderName("arn:aws:iam::111111111111:saml-provider/Okta"));
        assertNull(RoleImportPlanner.awsProviderName("not-an-arn"));
        assertNull(RoleImportPlanner.awsProviderName(null));
    }

    @Test
    void validate_acceptsGoodChoices() {
        assertNull(RoleImportPlanner.validate(List.of(new Choice("dev", "Dev"), new Choice(" prod ", "Prod")), Set.of("other")));
    }

    @Test
    void validate_rejectsBadChoices() {
        Set<String> existing = Set.of("taken");
        assertEquals("Select at least one role to import.", RoleImportPlanner.validate(List.of(), existing));
        assertTrue(RoleImportPlanner.validate(List.of(new Choice(" ", "x")), existing).contains("needs a profile name"));
        assertTrue(RoleImportPlanner.validate(List.of(new Choice("has space", "x")), existing).contains("can only contain"));
        assertTrue(RoleImportPlanner.validate(List.of(new Choice("a]b", "x")), existing).contains("can only contain"));
        assertTrue(RoleImportPlanner.validate(List.of(new Choice("Fed-x", "x")), existing).contains("reserved"));
        assertTrue(RoleImportPlanner.validate(List.of(new Choice("GLOBAL", "x")), existing).contains("reserved"));
        assertTrue(RoleImportPlanner.validate(List.of(new Choice("taken", "x")), existing).contains("already exists"));
        assertTrue(RoleImportPlanner.validate(List.of(new Choice("a", "x"), new Choice("a", "y")), existing).contains("more than one"));
        assertTrue(RoleImportPlanner.validate(List.of(new Choice("a", " ")), existing).contains("needs a GUI name"));
    }
}
