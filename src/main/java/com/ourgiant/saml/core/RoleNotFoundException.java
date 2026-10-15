package com.ourgiant.saml.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thrown when the SAML assertion doesn't contain the account/role a profile is configured for.
 * Carries the roles the assertion <em>does</em> contain so the error can show what's actually
 * available, since a typo, wrong account, or revoked access are the usual causes.
 */
public class RoleNotFoundException extends RuntimeException {

    private final String accountNumber;
    private final String iamRole;
    private final List<SamlRole> availableRoles;
    private final List<SamlRole> nearMatches;

    public RoleNotFoundException(String accountNumber, String iamRole, List<SamlRole> availableRoles) {
        this(accountNumber, iamRole, availableRoles, findNearMatches(availableRoles, accountNumber, iamRole));
    }

    private RoleNotFoundException(String accountNumber, String iamRole, List<SamlRole> availableRoles,
                                  List<SamlRole> nearMatches) {
        super(buildMessage(accountNumber, iamRole, availableRoles, nearMatches));
        this.accountNumber = accountNumber;
        this.iamRole = iamRole;
        this.availableRoles = List.copyOf(availableRoles);
        this.nearMatches = nearMatches;
    }

    public String accountNumber() {
        return accountNumber;
    }

    public String iamRole() {
        return iamRole;
    }

    public List<SamlRole> availableRoles() {
        return availableRoles;
    }

    /**
     * Roles that are probably what the profile meant: same role name ignoring case (anywhere),
     * then other roles in the same account, then the same role name in other accounts.
     */
    public List<SamlRole> nearMatches() {
        return nearMatches;
    }

    static List<SamlRole> findNearMatches(List<SamlRole> roles, String accountNumber, String iamRole) {
        List<SamlRole> caseOnly = new ArrayList<>();
        List<SamlRole> sameAccount = new ArrayList<>();
        List<SamlRole> sameRoleName = new ArrayList<>();
        String wantedRole = iamRole == null ? "" : iamRole.toLowerCase(Locale.ROOT);
        for (SamlRole role : roles) {
            boolean accountMatches = role.accountNumber().equals(accountNumber);
            boolean roleMatchesIgnoringCase = role.roleName().toLowerCase(Locale.ROOT).equals(wantedRole);
            if (accountMatches && roleMatchesIgnoringCase) {
                caseOnly.add(role);
            } else if (accountMatches) {
                sameAccount.add(role);
            } else if (roleMatchesIgnoringCase) {
                sameRoleName.add(role);
            }
        }
        List<SamlRole> result = new ArrayList<>(caseOnly);
        result.addAll(sameAccount);
        result.addAll(sameRoleName);
        return List.copyOf(result);
    }

    private static String buildMessage(String accountNumber, String iamRole, List<SamlRole> availableRoles,
                                       List<SamlRole> nearMatches) {
        String message = "Matching role not found in SAML response: " + accountNumber + "/" + iamRole;
        if (availableRoles.isEmpty()) {
            return message + " (the assertion contains no AWS roles)";
        }
        String count = availableRoles.size() == 1 ? "1 role" : availableRoles.size() + " roles";
        if (nearMatches.isEmpty()) {
            return message + " (assertion has " + count + ", none in account " + accountNumber + ")";
        }
        SamlRole closest = nearMatches.get(0);
        return message + " (assertion has " + count + "; closest: " + closest.accountNumber() + "/" + closest.roleName() + ")";
    }
}
