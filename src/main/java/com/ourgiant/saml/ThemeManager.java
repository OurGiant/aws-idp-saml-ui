package com.ourgiant.saml;

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatGitHubIJTheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
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

        try {
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
            logger.info("Applied theme: {}", themeName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to apply theme: {}", themeName, e);
            return false;
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
