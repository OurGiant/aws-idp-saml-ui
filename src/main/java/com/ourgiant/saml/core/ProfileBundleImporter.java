package com.ourgiant.saml.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies a {@link ProfileBundle} to this machine's samlsts and app settings (#199), with rules
 * chosen so an import can't quietly damage an existing setup:
 * <ul>
 *   <li>profiles are imported only as chosen, each either new, explicitly overwriting a profile
 *       of the same name, or renamed;</li>
 *   <li>identity providers ({@code Fed-*}) missing here are added, but an existing one is never
 *       overwritten (it's what every local profile on it logs in with);</li>
 *   <li>{@code [global]} only gains keys it doesn't already have — e.g. never someone else's username;</li>
 *   <li>settings are optional, limited to {@link ProfileBundle#EXPORTED_SETTINGS}, and pinned
 *       profiles are appended after the existing pins.</li>
 * </ul>
 */
public final class ProfileBundleImporter {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private ProfileBundleImporter() {
    }

    /** One profile from the bundle the user chose to import, as {@code targetName} (same as source unless renamed). */
    public record Choice(String sourceName, String targetName) {
    }

    /** What importing would do to settings sections, shown before the user confirms. */
    public record Preview(List<String> providersToAdd, List<String> providersKeptDifferent, List<String> globalKeysToAdd) {
    }

    public record Result(List<String> importedProfiles, List<String> overwrittenProfiles, List<String> providersAdded,
                         List<String> globalKeysAdded, boolean settingsApplied, String importedTheme) {
    }

    public static Preview preview(ProfileBundle bundle, Map<String, Map<String, String>> localSections) {
        List<String> toAdd = new ArrayList<>();
        List<String> keptDifferent = new ArrayList<>();
        List<String> globalKeys = new ArrayList<>();
        bundle.samlstsSections().forEach((name, fields) -> {
            if (ConfigSections.isProvider(name)) {
                Map<String, String> local = localSections.get(name);
                if (local == null) {
                    toAdd.add(name);
                } else if (!local.equals(fields)) {
                    keptDifferent.add(name);
                }
            } else if (ConfigSections.isGlobal(name)) {
                Map<String, String> local = findGlobal(localSections);
                for (String key : fields.keySet()) {
                    if (local == null || !local.containsKey(key)) {
                        globalKeys.add(key);
                    }
                }
            }
        });
        return new Preview(toAdd, keptDifferent, globalKeys);
    }

    /** Null if the choices can be applied, else a message describing the first problem. */
    public static String validate(List<Choice> choices, Set<String> existingProfiles, Set<String> confirmedOverwrites) {
        if (choices.isEmpty()) {
            return "Select at least one profile to import.";
        }
        Set<String> seen = new HashSet<>();
        for (Choice choice : choices) {
            String name = choice.targetName() == null ? "" : choice.targetName().trim();
            if (name.isEmpty()) {
                return "Every selected profile needs a name to import as.";
            }
            boolean renamed = !name.equals(choice.sourceName());
            if (renamed && !SAFE_NAME.matcher(name).matches()) {
                return "Profile name \"" + name + "\" can only contain letters, digits, '.', '_' and '-'.";
            }
            if (ConfigSections.isSettingsSection(name)) {
                return "Profile name \"" + name + "\" is reserved (\"global\" and \"Fed-*\" are samlsts settings sections).";
            }
            if (!seen.add(name)) {
                return "Profile name \"" + name + "\" is used for more than one selected profile.";
            }
            if (existingProfiles.contains(name) && !confirmedOverwrites.contains(name)) {
                return "A profile named \"" + name + "\" already exists. Rename it, or confirm overwriting it.";
            }
        }
        return null;
    }

    /** Profiles among {@code choices} that would replace an existing profile of the same name. */
    public static List<String> overwrites(List<Choice> choices, Set<String> existingProfiles) {
        return choices.stream().map(c -> c.targetName().trim()).filter(existingProfiles::contains).toList();
    }

    /**
     * Applies validated choices. samlsts is written once, so a failure leaves it unchanged; settings
     * are applied after it.
     */
    public static Result apply(ProfileBundle bundle, List<Choice> choices, boolean importSettings,
                               ConfigManager configManager, DatabaseManager databaseManager) throws Exception {
        Map<String, Map<String, String>> local = configManager.getAllSections();
        Set<String> existingProfiles = new HashSet<>(configManager.getAvailableProfiles());
        Preview preview = preview(bundle, local);

        Map<String, Map<String, String>> replaceWhole = new LinkedHashMap<>();
        for (String provider : preview.providersToAdd()) {
            replaceWhole.put(provider, bundle.samlstsSections().get(provider));
        }
        List<String> imported = new ArrayList<>();
        List<String> overwritten = new ArrayList<>();
        Map<String, String> renames = new LinkedHashMap<>();
        for (Choice choice : choices) {
            String target = choice.targetName().trim();
            Map<String, String> fields = bundle.profiles().get(choice.sourceName());
            if (fields == null) {
                continue;
            }
            replaceWhole.put(target, fields);
            imported.add(target);
            renames.put(choice.sourceName(), target);
            if (existingProfiles.contains(target)) {
                overwritten.add(target);
            }
        }

        Map<String, Map<String, String>> addMissingKeys = new LinkedHashMap<>();
        if (!preview.globalKeysToAdd().isEmpty()) {
            Map<String, String> bundleGlobal = findGlobal(bundle.samlstsSections());
            Map<String, String> keys = new LinkedHashMap<>();
            for (String key : preview.globalKeysToAdd()) {
                keys.put(key, bundleGlobal.get(key));
            }
            String localGlobalName = local.keySet().stream().filter(ConfigSections::isGlobal).findFirst().orElse("global");
            addMissingKeys.put(localGlobalName, keys);
        }

        configManager.importSections(replaceWhole, addMissingKeys);

        String theme = null;
        if (importSettings) {
            bundle.settings().forEach(databaseManager::setConfig);
            theme = bundle.settings().get("theme");
            List<String> pins = new ArrayList<>(databaseManager.getPinnedProfilesInOrder());
            Set<String> nowExisting = new HashSet<>(configManager.getAvailableProfiles());
            for (String pinned : bundle.pinnedProfiles()) {
                String target = renames.getOrDefault(pinned, pinned);
                if (nowExisting.contains(target) && !pins.contains(target)) {
                    pins.add(target);
                }
            }
            databaseManager.setPinnedProfilesInOrder(pins);
        }
        return new Result(imported, overwritten, preview.providersToAdd(), preview.globalKeysToAdd(), importSettings, theme);
    }

    private static Map<String, String> findGlobal(Map<String, Map<String, String>> sections) {
        for (Map.Entry<String, Map<String, String>> entry : sections.entrySet()) {
            if (ConfigSections.isGlobal(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
