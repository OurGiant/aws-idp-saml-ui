package com.ourgiant.saml.core;

import org.ini4j.Config;
import org.ini4j.Ini;
import org.ini4j.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages configuration reading from samlsts file
 */
public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);

    private final String configFilePath;
    private Ini config;
    private final DatabaseManager databaseManager;

    public ConfigManager() {
        this(true);
    }

    /** @param loadImmediately pass false when creating config for the first time (file not yet written) */
    public ConfigManager(boolean loadImmediately) {
        String homeDir = System.getProperty("user.home");
        Path awsDir = Paths.get(homeDir, ".aws");
        this.configFilePath = awsDir.resolve("samlsts").toString();
        this.databaseManager = new DatabaseManager();

        if (loadImmediately) {
            loadConfig();
        }
    }

    private void loadConfig() {
        try {
            File configFile = new File(configFilePath);
            if (!configFile.exists()) {
                throw new RuntimeException("Configuration file not found: " + configFilePath +
                    "\nPlease copy samlsts.demo to ~/.aws/samlsts and configure it.");
            }
            config = new Ini(configFile);
            disableValueEscaping(config);
            logger.info("Configuration loaded from: {}", configFilePath);
        } catch (Exception e) {
            logger.error("Failed to load configuration", e);
            throw new RuntimeException("Failed to load configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Get list of available profile names (excluding provider sections)
     */
    public List<String> getAvailableProfiles() {
        List<String> profiles = new ArrayList<>();
        Set<String> sections = config.keySet();

        for (String section : sections) {
            if (!ConfigSections.isSettingsSection(section)) {
                profiles.add(section);
            }
        }

        profiles.sort(String.CASE_INSENSITIVE_ORDER);
        return profiles;
    }

    /**
     * Get profile configuration
     */
    public Profile.Section getProfile(String profileName) {
        return config.get(profileName);
    }

    /**
     * Get provider configuration
     */
    public Profile.Section getProvider(String providerName) {
        return config.get(providerName);
    }

    /**
     * Get global configuration section
     */
    public Profile.Section getGlobalConfig() {
        return config.get("global");
    }

    /**
     * Looks up a key on the named profile, falling back to the same key on the global
     * section when the profile doesn't set it (or doesn't exist). Every profile-scoped
     * setting below follows this same precedence.
     */
    private String getWithGlobalFallback(String profileName, String key) {
        Profile.Section profile = getProfile(profileName);
        if (profile != null) {
            String value = profile.get(key);
            if (value != null) {
                return value;
            }
        }

        Profile.Section global = getGlobalConfig();
        return global != null ? global.get(key) : null;
    }

    /**
     * Get AWS region for a profile
     */
    public String getAwsRegion(String profileName) {
        String region = getWithGlobalFallback(profileName, "awsregion");
        return region != null ? region : "us-east-1";
    }

    /** The region actually configured for the profile (or globally), or null — no us-east-1 fallback (#226). */
    public String getExplicitAwsRegion(String profileName) {
        String region = getWithGlobalFallback(profileName, "awsregion");
        return region == null || region.isBlank() ? null : region.trim();
    }

    /** The profile's Open Console landing service id ({@code consoleservice}, #239), or null for Console Home. */
    public String getConsoleService(String profileName) {
        Profile.Section profile = getProfile(profileName);
        String service = profile != null ? profile.get("consoleservice") : null;
        return service == null || service.isBlank() ? null : service.trim();
    }

    /**
     * Get account number for a profile
     */
    public String getAccountNumber(String profileName) {
        return getWithGlobalFallback(profileName, "accountnumber");
    }

    /**
     * Get IAM role for a profile
     */
    public String getIamRole(String profileName) {
        return getWithGlobalFallback(profileName, "iamrole");
    }

    /**
     * Get SAML provider for a profile
     */
    public String getSamlProvider(String profileName) {
        return getWithGlobalFallback(profileName, "samlprovider");
    }

    /**
     * Get username for a profile
     */
    public String getUsername(String profileName) {
        String username = getWithGlobalFallback(profileName, "username");
        if (username != null) {
            return username;
        }

        // Legacy capitalized variant, global-only
        Profile.Section global = getGlobalConfig();
        return global != null ? global.get("User") : null;
    }

    /** The username for a login that isn't tied to any profile (role discovery): the global setting. */
    public String getDefaultUsername() {
        return getUsername(null);
    }

    /**
     * Session duration for a credential request, in seconds: the profile's own
     * {@code sessionduration} if it sets one (#219), otherwise the global default.
     */
    public int getSessionDuration(String profileName) {
        Integer own = getProfileSessionDuration(profileName);
        return own != null ? own : getDefaultSessionDuration();
    }

    /** The profile's own session duration override in seconds, or null when it uses the global default. */
    public Integer getProfileSessionDuration(String profileName) {
        Profile.Section profile = profileName != null ? getProfile(profileName) : null;
        String duration = profile != null ? profile.get("sessionduration") : null;
        if (duration == null || duration.isBlank()) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(duration.trim());
            return seconds > 0 ? seconds : null;
        } catch (NumberFormatException e) {
            logger.warn("Invalid session duration for profile {}: {}; using the default", profileName, duration);
            return null;
        }
    }

    /**
     * The global default session duration in seconds: the Configuration dialog's value, else a
     * legacy {@code [global] sessionduration}, else 4 hours. (The database value is seeded at
     * install, so in practice it's the Configuration setting.)
     */
    public int getDefaultSessionDuration() {
        int dbDuration = databaseManager.getSessionDuration();
        if (dbDuration > 0) {
            return dbDuration;
        }
        Profile.Section global = getGlobalConfig();
        if (global != null) {
            String duration = global.get("sessionduration");
            if (duration != null) {
                try {
                    return Integer.parseInt(duration.trim());
                } catch (NumberFormatException e) {
                    logger.warn("Invalid global session duration: {}", duration);
                }
            }
        }
        return 14400;
    }

    /** Smallest and largest session durations offered in the UI, in seconds. */
    public int getMinSessionDuration() {
        return databaseManager.getMinDuration();
    }

    public int getMaxSessionDuration() {
        return databaseManager.getMaxDuration();
    }

    /**
     * Get GUI name for a profile
     */
    public String getGuiName(String profileName) {
        Profile.Section profile = getProfile(profileName);
        if (profile != null) {
            String guiName = profile.get("guiname");
            if (guiName != null) {
                return guiName.trim();
            }
        }
        return null;
    }

    /**
     * Get browser type to use for SAML login
     */
    public String getBrowserType() {
        // 1. UI database value takes highest priority, if the user has explicitly set one
        String dbBrowser = databaseManager.getConfig("browser");
        if (dbBrowser != null && !dbBrowser.isEmpty()) {
            return dbBrowser;
        }

        // 2. Global samlsts config, set during first-run setup
        Profile.Section global = getGlobalConfig();
        if (global != null) {
            return global.get("browser", "chrome");
        }

        return "chrome";
    }

    /**
     * Get list of configured identity provider section names (e.g. "Fed-Okta")
     */
    public List<String> getIdpProviders() {
        List<String> idps = new ArrayList<>();
        for (String section : config.keySet()) {
            if (ConfigSections.isProvider(section)) {
                idps.add(section);
            }
        }
        idps.sort(String.CASE_INSENSITIVE_ORDER);
        return idps;
    }

    /**
     * Creates or updates a profile section with the given fields and persists to disk.
     */
    public void saveProfile(String profileName, Map<String, String> fields) throws Exception {
        saveProfile(profileName, fields, Set.of());
    }

    /** As {@link #saveProfile(String, Map)}, also removing {@code removeKeys} from the section (e.g. a cleared override). */
    public void saveProfile(String profileName, Map<String, String> fields, Set<String> removeKeys) throws Exception {
        Profile.Section section = config.get(profileName);
        if (section == null) {
            section = config.add(profileName);
        }
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            section.put(entry.getKey(), entry.getValue());
        }
        for (String key : removeKeys) {
            section.remove(key);
        }
        persistConfig();
    }

    /** Every samlsts section (global, identity providers, profiles) as plain maps, in file order. */
    public Map<String, Map<String, String>> getAllSections() {
        Map<String, Map<String, String>> sections = new java.util.LinkedHashMap<>();
        for (String name : config.keySet()) {
            Profile.Section section = config.get(name);
            Map<String, String> fields = new java.util.LinkedHashMap<>();
            for (String key : section.keySet()) {
                fields.put(key, section.get(key));
            }
            sections.put(name, fields);
        }
        return sections;
    }

    /**
     * Applies an import (#199) with a single write: each section in {@code replaceWhole} replaces
     * any existing section of that name entirely; each in {@code addMissingKeys} only gains keys
     * that section doesn't already have.
     */
    public void importSections(Map<String, Map<String, String>> replaceWhole,
                               Map<String, Map<String, String>> addMissingKeys) throws Exception {
        for (Map.Entry<String, Map<String, String>> entry : replaceWhole.entrySet()) {
            config.remove(entry.getKey());
            Profile.Section section = config.add(entry.getKey());
            entry.getValue().forEach(section::put);
        }
        for (Map.Entry<String, Map<String, String>> entry : addMissingKeys.entrySet()) {
            Profile.Section section = config.get(entry.getKey());
            if (section == null) {
                section = config.add(entry.getKey());
            }
            for (Map.Entry<String, String> field : entry.getValue().entrySet()) {
                if (!section.containsKey(field.getKey())) {
                    section.put(field.getKey(), field.getValue());
                }
            }
        }
        persistConfig();
    }

    /**
     * Removes a profile section, persists to disk, and drops its token_state row so it
     * doesn't linger in the database as an orphaned profile (see #101).
     */
    public void deleteProfile(String profileName) throws Exception {
        config.remove(profileName);
        persistConfig();
        databaseManager.deleteProfileState(profileName);
    }

    /**
     * ini4j's default Config escapes ':' (and other delimiter-like characters) in values, turning
     * URLs like "https://..." into "https\://..." on store(). The original Python CLI's ini parser
     * doesn't understand that escaping, so it breaks compatibility (see #89). {@code Ini} instances
     * share {@link Config#getGlobal()} by reference unless given their own, so this clones the config
     * onto this instance rather than mutating the shared global one out from under every other Ini.
     */
    private static void disableValueEscaping(Ini ini) {
        Config perInstanceConfig = ini.getConfig().clone();
        perInstanceConfig.setEscape(false);
        ini.setConfig(perInstanceConfig);
    }

    private void persistConfig() throws Exception {
        File configFile = new File(configFilePath);
        config.store(configFile);
        logger.info("Configuration file updated at: {}", configFilePath);
    }

    /**
     * Returns true if the config file exists on disk.
     */
    public static boolean configFileExists() {
        String homeDir = System.getProperty("user.home");
        Path configPath = Paths.get(homeDir, ".aws", "samlsts");
        return configPath.toFile().exists();
    }

    /**
     * Writes a new config file from the provided section map and reloads.
     * Keys in the outer map are section names; inner map entries are key=value pairs.
     */
    public void createConfig(Map<String, Map<String, String>> sections) throws Exception {
        File configFile = new File(configFilePath);
        configFile.getParentFile().mkdirs();

        Ini ini = new Ini();
        disableValueEscaping(ini);
        for (Map.Entry<String, Map<String, String>> section : sections.entrySet()) {
            ini.add(section.getKey());
            for (Map.Entry<String, String> entry : section.getValue().entrySet()) {
                ini.get(section.getKey()).add(entry.getKey(), entry.getValue());
            }
        }
        ini.store(configFile);
        logger.info("Configuration file created at: {}", configFilePath);

        loadConfig();
    }
}