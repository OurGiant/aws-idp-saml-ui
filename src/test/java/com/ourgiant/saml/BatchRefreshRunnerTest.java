package com.ourgiant.saml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(MockitoExtension.class)
class BatchRefreshRunnerTest {

    @Mock
    private ConfigManager configManager;

    @Mock
    private CredentialManager credentialManager;

    @Mock
    private PasswordManager passwordManager;

    @Mock
    private DatabaseManager databaseManager;

    private BatchRefreshRunner runner() {
        return new BatchRefreshRunner(configManager, credentialManager, passwordManager, databaseManager);
    }

    @Test
    void groupsProfilesSharingTheSameIdentityKey() {
        List<String> profiles = List.of("a", "b", "c", "d");

        List<List<String>> groups = BatchRefreshRunner.groupProfilesBySharedIdentity(profiles,
            p -> switch (p) {
                case "a", "c" -> "okta|alice";
                default -> "okta|bob";
            });

        assertEquals(List.of(List.of("a", "c"), List.of("b", "d")), groups);
    }

    @Test
    void eachDistinctIdentityIsItsOwnGroup() {
        List<String> profiles = List.of("a", "b", "c");

        List<List<String>> groups = BatchRefreshRunner.groupProfilesBySharedIdentity(profiles, p -> p);

        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c")), groups);
    }

    @Test
    void allProfilesShareOneGroupWhenIdentityKeyIsConstant() {
        List<String> profiles = List.of("a", "b", "c");

        List<List<String>> groups = BatchRefreshRunner.groupProfilesBySharedIdentity(profiles, p -> "same-identity");

        assertEquals(List.of(List.of("a", "b", "c")), groups);
    }

    @Test
    void emptyInputProducesNoGroups() {
        List<List<String>> groups = BatchRefreshRunner.groupProfilesBySharedIdentity(List.of(), p -> p);

        assertEquals(List.of(), groups);
    }

    @Test
    void run_stopsImmediatelyWhenAlreadyCancelled() {
        List<String> progress = new ArrayList<>();

        List<BatchRefreshRunner.Result> results = runner().run(
            List.of("profile-a", "profile-b"),
            true, // showBrowser=true skips the configManager-driven grouping lookup entirely
            () -> true, // cancelled on the very first check, before any group is processed
            authenticator -> fail("should never construct/track a SamlAuthenticator once already cancelled"),
            progress::add
        );

        assertEquals(List.of(), results);
        assertEquals(List.of(), progress);
    }
}
