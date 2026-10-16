package com.ourgiant.saml.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleDestinationTest {

    @Test
    void consolePageWinsOverRegion() {
        assertEquals("https://eu-west-1.console.aws.amazon.com/ec2/home?region=eu-west-1#Instances:",
            ConsoleDestination.resolve(" https://eu-west-1.console.aws.amazon.com/ec2/home?region=eu-west-1#Instances: ", "us-west-2"));
    }

    @Test
    void regionGivesThatRegionsConsoleHome() {
        assertEquals("https://eu-west-1.console.aws.amazon.com/console/home?region=eu-west-1",
            ConsoleDestination.resolve(null, "eu-west-1"));
        assertEquals(ConsoleDestination.DEFAULT, ConsoleDestination.resolve("", "us-gov-west-1"),
            "GovCloud regions have no console on console.aws.amazon.com");
        assertEquals(ConsoleDestination.DEFAULT, ConsoleDestination.resolve(null, "cn-north-1"));
    }

    @Test
    void noSettingsOrInvalidRegionKeepsTheDefault() {
        assertEquals(ConsoleDestination.DEFAULT, ConsoleDestination.resolve(null, null));
        assertEquals(ConsoleDestination.DEFAULT, ConsoleDestination.resolve("  ", "not a region"));
    }

    @Test
    void acceptsCommercialConsoleHosts() {
        assertNull(ConsoleDestination.problemWith("https://console.aws.amazon.com/cloudwatch/home"));
        assertNull(ConsoleDestination.problemWith("https://us-east-1.console.aws.amazon.com/s3/buckets"));
        assertNull(ConsoleDestination.problemWith("HTTPS://Console.AWS.Amazon.com/"));
    }

    @Test
    void rejectsAnythingThatIsNotAnAwsConsolePage() {
        assertTrue(ConsoleDestination.problemWith("http://console.aws.amazon.com/").contains("https://"));
        assertTrue(ConsoleDestination.problemWith("https://evil.example.com/console.aws.amazon.com").contains("not \"evil.example.com\""));
        assertTrue(ConsoleDestination.problemWith("https://console.aws.amazon.com.evil.example/").contains("AWS console address"));
        assertTrue(ConsoleDestination.problemWith("https://notconsole.aws.amazon.com/").contains("AWS console address"));
        assertTrue(ConsoleDestination.problemWith("https://user:pw@console.aws.amazon.com/").contains("user name"));
        assertTrue(ConsoleDestination.problemWith("https://console.aws.amazon.com:8443/").contains("port"));
        assertTrue(ConsoleDestination.problemWith("javascript:alert(1)").contains("https://"));
        assertTrue(ConsoleDestination.problemWith("https://console.amazonaws-us-gov.com/").contains("GovCloud"));
        assertTrue(ConsoleDestination.problemWith("https://console.amazonaws.cn/").contains("China"));
        assertTrue(ConsoleDestination.problemWith("not a url with spaces").contains("isn't a valid URL"));
    }

    @Test
    void resolveRefusesABadConsolePage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ConsoleDestination.resolve("https://evil.example.com/", "eu-west-1"));
        assertTrue(e.getMessage().contains("AWS console address"));
    }
}
