package com.ourgiant.saml.core;

/** How samlsts section names are classified: {@code [global]}, identity providers ({@code [Fed-*]}), and profiles. */
public final class ConfigSections {

    private ConfigSections() {
    }

    public static boolean isGlobal(String section) {
        return section.equalsIgnoreCase("global");
    }

    public static boolean isProvider(String section) {
        return section.startsWith("Fed-");
    }

    /** True for global and identity-provider sections, i.e. anything that isn't a profile. */
    public static boolean isSettingsSection(String section) {
        return isGlobal(section) || isProvider(section);
    }
}
