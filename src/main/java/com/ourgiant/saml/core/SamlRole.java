package com.ourgiant.saml.core;

/**
 * Represents a SAML role parsed from the assertion
 */
public record SamlRole(String roleArn, String principalArn, String accountNumber, String roleName) {

    public SamlRole(String roleArn, String principalArn) {
        this(roleArn, principalArn, parseAccountNumber(roleArn), parseRoleName(roleArn));
    }

    // Format: arn:aws:iam::123456789012:role/RoleName
    private static String parseAccountNumber(String roleArn) {
        String[] arnParts = roleArn.split(":");
        return arnParts.length >= 6 ? arnParts[4] : "unknown";
    }

    private static String parseRoleName(String roleArn) {
        String[] arnParts = roleArn.split(":");
        return arnParts.length >= 6 ? arnParts[5].substring(5) : "unknown"; // Remove "role/" prefix
    }

    /** The role in {@code roles} for exactly this account number and role name, or null if there isn't one. */
    public static SamlRole find(java.util.List<SamlRole> roles, String accountNumber, String roleName) {
        for (SamlRole role : roles) {
            if (role.accountNumber().equals(accountNumber) && role.roleName().equals(roleName)) {
                return role;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("SamlRole{account=%s, role=%s}", accountNumber, roleName);
    }
}
