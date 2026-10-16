package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionChoicesTest {

    @Test
    void includesStandardAndGovCloudRegionsWithNames() {
        assertEquals("us-east-1 — US East (N. Virginia)", RegionChoices.find("us-east-1").toString());
        assertEquals("eu-west-1", RegionChoices.find(" eu-west-1 ").id());
        assertTrue(RegionChoices.find("us-gov-west-1").name().contains("GovCloud"));
    }

    @Test
    void excludesPartitionsTheAppCantSignInToAndGlobalPseudoRegions() {
        for (String id : List.of("cn-north-1", "us-iso-east-1", "us-isob-east-1", "eusc-de-east-1", "aws-global", "aws-us-gov-global")) {
            assertNull(RegionChoices.find(id), id);
        }
    }

    @Test
    void listIsSortedWithGovCloudLastAndEveryIdIsAValidRegion() {
        List<RegionChoices.Choice> all = RegionChoices.ALL;
        assertTrue(all.size() >= 30, "expected the standard regions, got " + all.size());
        boolean seenGov = false;
        for (int i = 0; i < all.size(); i++) {
            RegionChoices.Choice c = all.get(i);
            assertTrue(ConsoleDestination.isValidRegion(c.id()), c.id());
            assertFalse(c.name().isBlank(), c.id());
            boolean gov = c.id().startsWith("us-gov-");
            assertFalse(seenGov && !gov, "GovCloud regions come last");
            seenGov |= gov;
            if (i > 0 && gov == all.get(i - 1).id().startsWith("us-gov-")) {
                assertTrue(all.get(i - 1).id().compareTo(c.id()) < 0, "sorted by id");
            }
        }
    }
}
