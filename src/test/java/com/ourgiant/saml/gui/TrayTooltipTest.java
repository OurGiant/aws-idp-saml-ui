package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayTooltipTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private static Instant in(Duration d) {
        return NOW.plus(d);
    }

    @Test
    void pinnedProfilesShownInPinOrderWithCountdowns() {
        String text = TrayTooltip.build(List.of("prod", "dev", "staging"),
            Map.of("dev", in(Duration.ofMinutes(42)), "prod", in(Duration.ofMinutes(-5)), "staging", in(Duration.ofMinutes(190))),
            List.of("dev", "prod", "staging"), NOW);

        assertEquals("AWS IdP SAML UI\nprod: expired · dev: 42m left · staging: 3h 10m left", text);
    }

    @Test
    void withoutPinsShowsSoonestExpiringProfilesWithCredentials() {
        String text = TrayTooltip.build(List.of(),
            Map.of("a", in(Duration.ofHours(3)), "b", in(Duration.ofMinutes(10)), "c", in(Duration.ofMinutes(-1)), "d", in(Duration.ofHours(5))),
            List.of("a", "b", "c", "d"), NOW);

        assertEquals("AWS IdP SAML UI\nc: expired · b: 10m left · a: 3h 0m left · +1 more", text);
    }

    @Test
    void pinnedProfileWithoutCredentialsSaysSo_andRemovedPinsAreSkipped() {
        String text = TrayTooltip.build(List.of("gone", "newbie"), Map.of(), List.of("newbie"), NOW);

        assertEquals("AWS IdP SAML UI\nnewbie: no credentials", text);
    }

    @Test
    void nothingToShowIsJustTheTitle() {
        assertEquals("AWS IdP SAML UI", TrayTooltip.build(List.of(), Map.of(), List.of("x"), NOW));
    }

    @Test
    void neverExceedsWindowsLimit_droppingWholeEntriesFirst() {
        List<String> names = List.of("a-very-long-profile-name-one", "a-very-long-profile-name-two",
            "a-very-long-profile-name-three", "a-very-long-profile-name-four");
        Map<String, Instant> exp = Map.of(names.get(0), in(Duration.ofMinutes(5)), names.get(1), in(Duration.ofMinutes(6)),
            names.get(2), in(Duration.ofMinutes(7)), names.get(3), in(Duration.ofMinutes(8)));

        String text = TrayTooltip.build(names, exp, names, NOW);

        assertTrue(text.length() <= TrayTooltip.MAX_LENGTH, text.length() + ": " + text);
        assertTrue(text.contains("a-very-long-profile…: 5m left"), text);
        assertTrue(text.endsWith(" more"), text);
    }

    @Test
    void describeFormatsDurations() {
        assertEquals("<1m left", TrayTooltip.describe(in(Duration.ofSeconds(30)), NOW));
        assertEquals("59m left", TrayTooltip.describe(in(Duration.ofMinutes(59).plusSeconds(59)), NOW));
        assertEquals("1h 0m left", TrayTooltip.describe(in(Duration.ofMinutes(60)), NOW));
        assertEquals("expired", TrayTooltip.describe(NOW, NOW));
        assertEquals("no credentials", TrayTooltip.describe(null, NOW));
    }
}
