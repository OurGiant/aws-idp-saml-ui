package com.ourgiant.saml.gui;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The main window's last normal (un-maximized) bounds plus whether it was maximized, persisted
 * so the window reopens where the user left it. Kept free of any live-window state so the
 * screen-fitting rules can be tested without a display.
 */
record WindowBounds(Rectangle normalBounds, boolean maximized) {

    /** How much of the window must still be on some screen to restore it there rather than recenter it. */
    static final int MIN_VISIBLE_WIDTH = 120;
    static final int MIN_VISIBLE_HEIGHT = 40;

    WindowBounds {
        normalBounds = new Rectangle(normalBounds);
    }

    /** Format: {@code x,y,width,height,maximized} — e.g. {@code 100,80,900,600,false}. */
    String serialize() {
        Rectangle r = normalBounds;
        return r.x + "," + r.y + "," + r.width + "," + r.height + "," + maximized;
    }

    /** Null for a missing, malformed, or non-positive-size value (treated as "nothing saved"). */
    static WindowBounds parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.trim().split(",");
        if (parts.length != 5) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int width = Integer.parseInt(parts[2].trim());
            int height = Integer.parseInt(parts[3].trim());
            if (width <= 0 || height <= 0) {
                return null;
            }
            return new WindowBounds(new Rectangle(x, y, width, height), Boolean.parseBoolean(parts[4].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The bounds to actually restore on the given screens' usable areas, or null if the saved
     * position is no longer meaningfully visible (e.g. it was on a monitor that's now
     * disconnected) and the caller should fall back to its default placement.
     *
     * <p>The window goes on whichever screen it overlaps most; its size is grown to at least
     * {@code minimumSize}, capped at that screen's size, and it's then shifted (not resized) so
     * it lies entirely on that screen.
     */
    Rectangle fitTo(List<Rectangle> screens, Dimension minimumSize) {
        Rectangle best = null;
        long bestArea = 0;
        for (Rectangle screen : screens) {
            Rectangle overlap = screen.intersection(normalBounds);
            if (overlap.isEmpty() || overlap.width < Math.min(MIN_VISIBLE_WIDTH, normalBounds.width)
                    || overlap.height < Math.min(MIN_VISIBLE_HEIGHT, normalBounds.height)) {
                continue;
            }
            long area = (long) overlap.width * overlap.height;
            if (area > bestArea) {
                bestArea = area;
                best = screen;
            }
        }
        if (best == null) {
            return null;
        }

        int width = Math.min(Math.max(normalBounds.width, minimumSize.width), best.width);
        int height = Math.min(Math.max(normalBounds.height, minimumSize.height), best.height);
        int x = clamp(normalBounds.x, best.x, best.x + best.width - width);
        int y = clamp(normalBounds.y, best.y, best.y + best.height - height);
        return new Rectangle(x, y, width, height);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /** Usable area (excluding taskbars/docks/panels) of every connected screen. */
    static List<Rectangle> currentScreens() {
        List<Rectangle> screens = new ArrayList<>();
        if (GraphicsEnvironment.isHeadless()) {
            return screens;
        }
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration config = device.getDefaultConfiguration();
            Rectangle bounds = config.getBounds();
            Insets insets = toolkit.getScreenInsets(config);
            screens.add(new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom));
        }
        return screens;
    }
}
