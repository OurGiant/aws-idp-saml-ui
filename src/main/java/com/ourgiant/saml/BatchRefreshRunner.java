package com.ourgiant.saml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates a batch credential refresh: groups profiles that can share one SAML login (see
 * #124), drives a SamlAuthenticator through each group, and collects a per-profile result. Has
 * no Swing dependency, so the orchestration/grouping logic is unit-testable without a running UI
 * (#153) - the caller supplies UI-facing behavior (progress text, cancellation, tracking the
 * in-flight authenticator for Cancel) via plain callbacks instead.
 */
class BatchRefreshRunner {

    record Result(String profile, boolean success, String detail) {
    }

    private final ConfigManager configManager;
    private final CredentialManager credentialManager;
    private final PasswordManager passwordManager;
    private final DatabaseManager databaseManager;

    BatchRefreshRunner(ConfigManager configManager, CredentialManager credentialManager,
                        PasswordManager passwordManager, DatabaseManager databaseManager) {
        this.configManager = configManager;
        this.credentialManager = credentialManager;
        this.passwordManager = passwordManager;
        this.databaseManager = databaseManager;
    }

    /**
     * Groups profiles that can share a single SAML login: same identity key (in practice,
     * samlProvider + username), each group in first-seen order. Pure/static so the grouping
     * logic itself is unit-testable without ConfigManager or live Swing state.
     */
    static List<List<String>> groupProfilesBySharedIdentity(List<String> profiles,
                                                              Function<String, String> identityKeyFn) {
        Map<String, List<String>> byIdentity = new LinkedHashMap<>();
        for (String profile : profiles) {
            byIdentity.computeIfAbsent(identityKeyFn.apply(profile), k -> new ArrayList<>()).add(profile);
        }
        return new ArrayList<>(byIdentity.values());
    }

    List<Result> run(List<String> targets, boolean showBrowser, BooleanSupplier cancelled,
                      Consumer<SamlAuthenticator> onActiveAuthenticatorChanged,
                      Consumer<String> progressCallback) {
        List<Result> results = new ArrayList<>();

        // Watching the browser only makes sense one login at a time (the visual role-selection
        // step doesn't generalize to a shared login) - keep every profile its own group in that
        // case. Otherwise, profiles on the same identity provider + username share one login's
        // SAML assertion (see #124) instead of logging in once per profile.
        List<List<String>> groups = showBrowser
            ? targets.stream().map(List::of).collect(Collectors.toList())
            : groupProfilesBySharedIdentity(targets,
                p -> configManager.getSamlProvider(p) + "|" + configManager.getUsername(p));

        int completed = 0;
        groupLoop:
        for (List<String> group : groups) {
            if (cancelled.getAsBoolean()) {
                break;
            }

            String representativeProfile = group.get(0);
            String loginPrefix = group.size() > 1
                ? "Logging in once for " + group.size() + " profiles sharing "
                    + representativeProfile + "'s identity provider: "
                : "Refreshing " + (completed + 1) + " of " + targets.size()
                    + " (" + representativeProfile + "): ";
            progressCallback.accept(loginPrefix + "starting...");

            SamlAuthenticator authenticator = new SamlAuthenticator(configManager, credentialManager, passwordManager);
            onActiveAuthenticatorChanged.accept(authenticator);
            String assertion;
            try {
                assertion = authenticator.performLoginAndGetAssertion(
                    representativeProfile,
                    databaseManager.getFastPassEnabled(),
                    showBrowser,
                    msg -> progressCallback.accept(loginPrefix + msg)
                );
            } catch (Exception ex) {
                onActiveAuthenticatorChanged.accept(null);
                if (cancelled.getAsBoolean()) {
                    // Cancelled mid-flight: don't report the interrupted group as failures.
                    break;
                }
                // The shared login itself failed: every profile in the group failed with it.
                CredentialRequestError error = CredentialRequestError.classify(ex);
                for (String profile : group) {
                    results.add(new Result(profile, false, error.statusMessage()));
                }
                completed += group.size();
                continue;
            }
            onActiveAuthenticatorChanged.accept(null);

            for (String profile : group) {
                if (cancelled.getAsBoolean()) {
                    break groupLoop;
                }
                completed++;
                String progressPrefix = "Refreshing " + completed + " of " + targets.size()
                    + " (" + profile + "): ";
                progressCallback.accept(progressPrefix + "assuming role...");
                try {
                    authenticator.assumeRoleAndSaveCredentials(profile, assertion,
                        msg -> progressCallback.accept(progressPrefix + msg));
                    results.add(new Result(profile, true, null));
                } catch (Exception ex) {
                    CredentialRequestError error = CredentialRequestError.classify(ex);
                    results.add(new Result(profile, false, error.statusMessage()));
                }
            }
        }
        return results;
    }
}
