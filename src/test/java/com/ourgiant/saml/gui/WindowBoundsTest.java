package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WindowBoundsTest {

    private static final Rectangle LEFT = new Rectangle(0, 0, 1920, 1040);
    private static final Rectangle RIGHT = new Rectangle(1920, 0, 2560, 1400);
    private static final Dimension MIN = new Dimension(700, 450);

    @Test
    void serializeAndParse_roundTrip() {
        WindowBounds bounds = new WindowBounds(new Rectangle(-40, 25, 900, 600), true);

        assertEquals("-40,25,900,600,true", bounds.serialize());
        assertEquals(bounds, WindowBounds.parse(bounds.serialize()));
    }

    @Test
    void parse_rejectsMissingOrMalformedValues() {
        assertNull(WindowBounds.parse(null));
        assertNull(WindowBounds.parse(""));
        assertNull(WindowBounds.parse("1,2,3"));
        assertNull(WindowBounds.parse("a,b,c,d,false"));
        assertNull(WindowBounds.parse("10,10,0,500,false"));
        assertNull(WindowBounds.parse("10,10,800,-1,false"));
    }

    @Test
    void fitTo_keepsBoundsThatAlreadyFit() {
        Rectangle saved = new Rectangle(2100, 100, 1000, 700);

        assertEquals(saved, new WindowBounds(saved, false).fitTo(List.of(LEFT, RIGHT), MIN));
    }

    @Test
    void fitTo_returnsNullWhenSavedMonitorIsGone() {
        Rectangle onDisconnectedRightMonitor = new Rectangle(2100, 100, 1000, 700);

        assertNull(new WindowBounds(onDisconnectedRightMonitor, false).fitTo(List.of(LEFT), MIN));
    }

    @Test
    void fitTo_returnsNullWhenOnlyASliverIsVisible() {
        Rectangle mostlyOffLeftEdge = new Rectangle(-950, 100, 1000, 700); // 50px visible

        assertNull(new WindowBounds(mostlyOffLeftEdge, false).fitTo(List.of(LEFT), MIN));
    }

    @Test
    void fitTo_shiftsPartlyOffScreenWindowFullyOntoScreen() {
        Rectangle hangingOffBottomRight = new Rectangle(1500, 800, 1000, 700);

        assertEquals(new Rectangle(920, 340, 1000, 700),
            new WindowBounds(hangingOffBottomRight, false).fitTo(List.of(LEFT), MIN));
    }

    @Test
    void fitTo_picksScreenWithMostOverlapWhenStraddling() {
        Rectangle mostlyOnRight = new Rectangle(1800, 100, 1000, 700);

        Rectangle fitted = new WindowBounds(mostlyOnRight, false).fitTo(List.of(LEFT, RIGHT), MIN);

        assertEquals(new Rectangle(1920, 100, 1000, 700), fitted);
    }

    @Test
    void fitTo_growsToMinimumAndCapsAtScreenSize() {
        assertEquals(new Rectangle(100, 100, 700, 450),
            new WindowBounds(new Rectangle(100, 100, 300, 200), false).fitTo(List.of(LEFT), MIN));

        assertEquals(new Rectangle(0, 0, 1920, 1040),
            new WindowBounds(new Rectangle(0, 0, 3000, 2000), false).fitTo(List.of(LEFT), MIN));
    }
}
