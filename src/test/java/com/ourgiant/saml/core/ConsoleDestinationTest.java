package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleDestinationTest {

    @Test
    void serviceInTheProfilesRegion() {
        assertEquals("https://eu-west-1.console.aws.amazon.com/ec2/home?region=eu-west-1",
            ConsoleDestination.resolve("ec2", "eu-west-1"));
        assertEquals("https://us-east-1.console.aws.amazon.com/codesuite/codepipeline/home?region=us-east-1",
            ConsoleDestination.resolve("codepipeline", " us-east-1 "));
    }

    @Test
    void serviceWithoutRegionUsesTheRegionlessPage() {
        assertEquals("https://console.aws.amazon.com/lambda/home", ConsoleDestination.resolve("lambda", null));
    }

    @Test
    void noServiceGivesConsoleHome_inTheRegionWhenSet() {
        assertEquals("https://eu-west-1.console.aws.amazon.com/console/home?region=eu-west-1",
            ConsoleDestination.resolve(null, "eu-west-1"));
        assertEquals(ConsoleDestination.DEFAULT, ConsoleDestination.resolve(null, null));
        assertEquals(ConsoleDestination.DEFAULT, ConsoleDestination.resolve("", "not a region"));
    }

    @Test
    void unknownHandEditedServiceFallsBackToConsoleHome() {
        assertEquals(ConsoleDestination.DEFAULT, ConsoleDestination.resolve("https://evil.example.com/", null));
        assertEquals("https://eu-west-1.console.aws.amazon.com/console/home?region=eu-west-1",
            ConsoleDestination.resolve("nosuchservice", "eu-west-1"));
    }

    @Test
    void govCloudAndChinaRegionsUseTheRegionlessCommercialPage() {
        assertEquals(ConsoleDestination.DEFAULT, ConsoleDestination.resolve(null, "us-gov-west-1"));
        assertEquals("https://console.aws.amazon.com/s3/home", ConsoleDestination.resolve("s3", "cn-north-1"));
    }

    @Test
    void catalogIsWellFormed() {
        List<ConsoleService> all = ConsoleService.ALL;
        assertTrue(all.size() >= 25 && all.size() <= 50, "25–50 services, got " + all.size());
        Set<String> ids = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (ConsoleService service : all) {
            assertTrue(ids.add(service.id()), "duplicate id " + service.id());
            assertTrue(labels.add(service.label()), "duplicate label " + service.label());
            assertTrue(service.id().matches("[a-z0-9]+"), "id " + service.id());
            assertTrue(service.path().matches("[a-z0-9-]+(/[a-z0-9-]+)*"), "path " + service.path());
            assertEquals(service, ConsoleService.find(service.id()));
        }
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).label().compareToIgnoreCase(all.get(i).label()) < 0, "alphabetical");
        }
        assertNull(ConsoleService.find(null));
        assertNull(ConsoleService.find(" "));
    }
}
