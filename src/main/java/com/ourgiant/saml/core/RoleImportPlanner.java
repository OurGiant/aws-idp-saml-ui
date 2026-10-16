package com.ourgiant.saml.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the roles from a SAML assertion into profile-import candidates (#194): flags roles that
 * already have a profile or can't work with the chosen identity provider, suggests unique
 * profile/GUI names for the rest, and validates the names the user ends up choosing. No Swing
 * here, so the rules are testable on their own.
 */
public final class RoleImportPlanner {

    /** Characters that are safe in both a samlsts section name and an AWS CLI {@code --profile} argument. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private RoleImportPlanner() {
    }

    /**
     * @param existingProfile name of a profile that already targets this account/role, or null
     * @param awsProviderMismatch the assertion's AWS SAML provider name when it differs from the
     *                            chosen {@code Fed-*} section's (a profile couldn't assume the role), else null
     */
    /** @param accountAlias the account's alias when AWS showed one (#244), else null */
    public record Candidate(SamlRole role, String existingProfile, String awsProviderMismatch,
                            String suggestedProfileName, String suggestedGuiName, String accountAlias) {

        public boolean importable() {
            return existingProfile == null && awsProviderMismatch == null;
        }
    }

    /** What an existing profile points at, for duplicate detection. */
    public record ExistingProfile(String accountNumber, String iamRole) {
    }

    /**
     * @param samlProvider the {@code Fed-*} section the roles were discovered through; its suffix is
     *                     the AWS SAML provider name every profile using it assumes roles with
     * @param existing     current profiles by name
     */
    public static List<Candidate> plan(List<SamlRole> roles, String samlProvider, Map<String, ExistingProfile> existing) {
        return plan(roles, samlProvider, existing, Map.of());
    }

    /**
     * @param accountAliases account number to alias (#244). When an account has one, names are based on
     *                       it ({@code <alias>-<role>}, "Role (alias)"), which people recognize far more
     *                       readily than a 12-digit number.
     */
    public static List<Candidate> plan(List<SamlRole> roles, String samlProvider, Map<String, ExistingProfile> existing,
                                       Map<String, String> accountAliases) {
        String expectedAwsProvider = samlProvider.startsWith("Fed-") ? samlProvider.substring(4) : samlProvider;

        // An assertion can list the same role twice (e.g. via two IdP group mappings).
        Map<String, SamlRole> unique = new LinkedHashMap<>();
        for (SamlRole role : roles) {
            unique.putIfAbsent(role.roleArn(), role);
        }
        List<SamlRole> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(SamlRole::accountNumber).thenComparing(SamlRole::roleName, String.CASE_INSENSITIVE_ORDER));

        Set<String> takenNames = new HashSet<>(existing.keySet());
        List<Candidate> candidates = new ArrayList<>();
        for (SamlRole role : sorted) {
            String existingProfile = findExisting(existing, role);
            String awsProvider = awsProviderName(role.principalArn());
            String mismatch = awsProvider != null && !awsProvider.equals(expectedAwsProvider) ? awsProvider : null;

            String profileName = null;
            if (existingProfile == null && mismatch == null) {
                String accountPart = accountAliases.getOrDefault(role.accountNumber(), role.accountNumber());
                profileName = uniqueName(sanitize(accountPart + "-" + role.roleName()), takenNames);
                takenNames.add(profileName);
            }
            String alias = accountAliases.get(role.accountNumber());
            candidates.add(new Candidate(role, existingProfile, mismatch, profileName,
                role.roleName() + " (" + (alias != null ? alias : role.accountNumber()) + ")", alias));
        }
        return candidates;
    }

    /** One profile choice from the import dialog, for {@link #validate}. */
    public record Choice(String profileName, String guiName) {
    }

    /** Null if the chosen names can all be saved, else a message describing the first problem. */
    public static String validate(List<Choice> choices, Set<String> existingProfiles) {
        if (choices.isEmpty()) {
            return "Select at least one role to import.";
        }
        Set<String> seen = new HashSet<>();
        for (Choice choice : choices) {
            String name = choice.profileName() == null ? "" : choice.profileName().trim();
            if (name.isEmpty()) {
                return "Every selected role needs a profile name.";
            }
            if (!SAFE_NAME.matcher(name).matches()) {
                return "Profile name \"" + name + "\" can only contain letters, digits, '.', '_' and '-'.";
            }
            if (name.startsWith("Fed-") || name.equalsIgnoreCase("global")) {
                return "Profile name \"" + name + "\" is reserved (\"global\" and \"Fed-*\" are samlsts settings sections).";
            }
            if (existingProfiles.contains(name)) {
                return "A profile named \"" + name + "\" already exists.";
            }
            if (!seen.add(name)) {
                return "Profile name \"" + name + "\" is used for more than one selected role.";
            }
            if (choice.guiName() == null || choice.guiName().isBlank()) {
                return "Profile \"" + name + "\" needs a GUI name.";
            }
        }
        return null;
    }

    private static String findExisting(Map<String, ExistingProfile> existing, SamlRole role) {
        for (Map.Entry<String, ExistingProfile> entry : existing.entrySet()) {
            ExistingProfile profile = entry.getValue();
            if (role.accountNumber().equals(profile.accountNumber()) && role.roleName().equals(profile.iamRole())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** {@code arn:aws:iam::123456789012:saml-provider/Okta} → {@code Okta}; null if not that shape. */
    static String awsProviderName(String principalArn) {
        int index = principalArn == null ? -1 : principalArn.indexOf(":saml-provider/");
        return index < 0 ? null : principalArn.substring(index + ":saml-provider/".length());
    }

    static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return cleaned.isEmpty() ? "profile" : cleaned.toLowerCase(Locale.ROOT);
    }

    private static String uniqueName(String base, Set<String> taken) {
        if (!taken.contains(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + "-" + n;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }
}
