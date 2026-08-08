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

    @Override
    public String toString() {
        return String.format("SamlRole{account=%s, role=%s}", accountNumber, roleName);
    }
}
