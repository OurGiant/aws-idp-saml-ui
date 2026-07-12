package com.ourgiant.saml;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.intellijthemes.FlatArcDarkOrangeIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatSolarizedDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubIJTheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Theme manager for handling both Swing default LaFs and FlatLaf themes.
 * Provides modern, flat design themes including dark mode.
 */
public class ThemeManager {
    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);

    private static final Map<String, ThemeInfo> AVAILABLE_THEMES = new LinkedHashMap<>();

    static {
        // System themes first
        UIManager.LookAndFeelInfo[] lafs = UIManager.getInstalledLookAndFeels();
        for (UIManager.LookAndFeelInfo laf : lafs) {
            AVAILABLE_THEMES.put(laf.getName(), new ThemeInfo(laf.getClassName(), false));
        }

        // FlatLaf themes
        AVAILABLE_THEMES.put("Flat Light", new ThemeInfo(FlatLightLaf.class.getName(), true));
        AVAILABLE_THEMES.put("GitHub Light", new ThemeInfo(FlatGitHubIJTheme.class.getName(), true));
        AVAILABLE_THEMES.put("GitHub Dark", new ThemeInfo(FlatGitHubDarkIJTheme.class.getName(), true));

        // Additional dark theme candidates — higher-contrast alternatives to GitHub Dark
        AVAILABLE_THEMES.put("Flat Dark", new ThemeInfo(FlatDarkLaf.class.getName(), true));
        AVAILABLE_THEMES.put("Darcula", new ThemeInfo(FlatDarculaLaf.class.getName(), true));
        AVAILABLE_THEMES.put("One Dark", new ThemeInfo(FlatOneDarkIJTheme.class.getName(), true));
        AVAILABLE_THEMES.put("Arc Dark Orange", new ThemeInfo(FlatArcDarkOrangeIJTheme.class.getName(), true));
        AVAILABLE_THEMES.put("Solarized Dark", new ThemeInfo(FlatSolarizedDarkIJTheme.class.getName(), true));
    }

    /**
     * Get all available theme names
     */
    public static String[] getAvailableThemeNames() {
        return AVAILABLE_THEMES.keySet().toArray(new String[0]);
    }

    /**
     * Apply the specified theme
     */
    public static boolean applyTheme(String themeName) {
        ThemeInfo themeInfo = AVAILABLE_THEMES.get(themeName);
        if (themeInfo == null) {
            logger.warn("Theme not found: {}", themeName);
            return false;
        }

        // Only FlatLaf targets get the snapshot/crossfade treatment — it relies on FlatLaf's
        // own repaint hooks. This is the isFlatLaf flag's real job: gate the animation, since
        // both branches below otherwise apply the theme the same way.
        boolean animate = themeInfo.isFlatLaf;
        if (animate) {
            FlatAnimatedLafChange.showSnapshot();
        }

        try {
            LookAndFeel currentLaf = UIManager.getLookAndFeel();
            if (currentLaf != null && currentLaf.getName().contains("Nimbus")) {
                // Nimbus leaves lazily-evaluated DerivedColor/painter defaults in the
                // UIManager defaults table that survive setLookAndFeel() and bleed into
                // whatever LaF follows — not just FlatLaf targets. Key this on "leaving
                // Nimbus", and flush by installing a plain intermediate LaF and refreshing
                // every window before applying the theme actually requested.
                flushStaleNimbusDefaults();
            }

            if (themeInfo.isFlatLaf) {
                // Instantiate by class name — works for all FlatLaf and IntelliJ themes
                LookAndFeel laf = (LookAndFeel) Class.forName(themeInfo.className)
                    .getDeclaredConstructor()
                    .newInstance();
                UIManager.setLookAndFeel(laf);
            } else {
                // Standard Swing LaF
                UIManager.setLookAndFeel(themeInfo.className);
            }

            // Refresh every open window, including dialogs like the Configuration dialog
            // itself, so the switch takes effect live. Windows that aren't displayable yet
            // (e.g. the main frame mid-construction, before its first pack()/setVisible())
            // are skipped — touching their UI before they have a native peer is what left
            // stale (0,0) screen-position caches behind, corrupting the first popup/menu.
            refreshDisplayableWindows();

            logger.info("Applied theme: {}", themeName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to apply theme: {}", themeName, e);
            return false;
        } finally {
            if (animate) {
                FlatAnimatedLafChange.hideSnapshotWithAnimation();
            }
        }
    }

    /**
     * Installs a plain intermediate FlatLaf and flushes it across all open windows to clear
     * stale Nimbus defaults before the real target theme is applied. If the intermediate
     * install itself fails, log and fall through — the normal apply below still runs.
     */
    private static void flushStaleNimbusDefaults() {
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
            refreshDisplayableWindows();
        } catch (Exception e) {
            logger.warn("Failed to flush stale Nimbus defaults via intermediate LaF; proceeding with direct theme switch", e);
        }
    }

    /**
     * Updates the UI of every open, displayable window. Windows that aren't displayable yet
     * (no native peer — e.g. before their first pack()/setVisible()) are skipped: refreshing
     * them prematurely is what corrupted the first popup/menu position after theme switches.
     */
    private static void refreshDisplayableWindows() {
        for (Window window : Window.getWindows()) {
            if (!window.isDisplayable()) {
                continue;
            }
            SwingUtilities.updateComponentTreeUI(window);
            window.revalidate();
            window.repaint();
        }
    }

    /**
     * Internal theme information class
     */
    private static class ThemeInfo {
        String className;
        boolean isFlatLaf;

        ThemeInfo(String className, boolean isFlatLaf) {
            this.className = className;
            this.isFlatLaf = isFlatLaf;
        }
    }
}
