package com.ourgiant.saml;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwingMainProfileGroupingTest {

    @Test
    void groupsProfilesSharingTheSameIdentityKey() {
        List<String> profiles = List.of("a", "b", "c", "d");

        List<List<String>> groups = SwingMain.groupProfilesBySharedIdentity(profiles,
            p -> switch (p) {
                case "a", "c" -> "okta|alice";
                default -> "okta|bob";
            });

        assertEquals(List.of(List.of("a", "c"), List.of("b", "d")), groups);
    }

    @Test
    void eachDistinctIdentityIsItsOwnGroup() {
        List<String> profiles = List.of("a", "b", "c");

        List<List<String>> groups = SwingMain.groupProfilesBySharedIdentity(profiles, p -> p);

        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c")), groups);
    }

    @Test
    void allProfilesShareOneGroupWhenIdentityKeyIsConstant() {
        List<String> profiles = List.of("a", "b", "c");

        List<List<String>> groups = SwingMain.groupProfilesBySharedIdentity(profiles, p -> "same-identity");

        assertEquals(List.of(List.of("a", "b", "c")), groups);
    }

    @Test
    void emptyInputProducesNoGroups() {
        List<List<String>> groups = SwingMain.groupProfilesBySharedIdentity(List.of(), p -> p);

        assertEquals(List.of(), groups);
    }
}
