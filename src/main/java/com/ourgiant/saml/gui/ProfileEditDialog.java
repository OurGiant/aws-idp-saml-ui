package com.ourgiant.saml.gui;

import com.ourgiant.saml.core.ConfigManager;
import com.ourgiant.saml.core.ConsoleDestination;
import com.ourgiant.saml.core.ConsoleService;

import org.ini4j.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Dialog for adding or editing a single AWS profile in ~/.aws/samlsts.
 */
public class ProfileEditDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(ProfileEditDialog.class);

    private final ConfigManager configManager;
    private final String originalProfileName; // null when adding a new profile
    // When duplicating (#229): the source profile's whole samlsts section, so keys this dialog doesn't
    // show (awsregion, username, ...) are copied too. Null otherwise.
    private final Map<String, String> template;
    private String savedProfileName;

    private JTextField profileNameField;
    private JTextField guiNameField;
    private JComboBox<String> samlProviderCombo;
    private JTextField accountNumberField;
    private JTextField iamRoleField;
    private JTextField regionField;
    private JComboBox<Object> consoleServiceCombo;
    /** First dropdown entry: no service chosen, so Open Console shows Console Home. */
    private static final String CONSOLE_HOME = "Console Home";
    private JCheckBox useDefaultDurationCheckBox;
    private JSpinner durationSpinner;

    private boolean saved = false;

    public ProfileEditDialog(Frame parent, ConfigManager configManager, String profileName) {
        this(parent, configManager, profileName, null, profileName == null ? "Add Profile" : "Edit Profile");
    }

    /** Add Profile pre-filled from {@code template} (a profile's samlsts section) under {@code suggestedName}. */
    static ProfileEditDialog forDuplicate(Frame parent, ConfigManager configManager, Map<String, String> template,
                                          String suggestedName) {
        ProfileEditDialog dialog = new ProfileEditDialog(parent, configManager, null, template, "Duplicate Profile");
        dialog.profileNameField.setText(suggestedName);
        dialog.profileNameField.selectAll();
        return dialog;
    }

    private ProfileEditDialog(Frame parent, ConfigManager configManager, String profileName,
                              Map<String, String> template, String title) {
        super(parent, title, true);
        this.configManager = configManager;
        this.originalProfileName = profileName;
        this.template = template;

        initializeUI();
        if (profileName != null) {
            loadExistingProfile(profileName);
        } else if (template != null) {
            loadFields(template, true);
        }
        pack();
        setMinimumSize(new Dimension(380, getHeight()));
        setLocationRelativeTo(parent);
    }

    public boolean isSaved() {
        return saved;
    }

    /** The name the profile was saved under (after a save). */
    public String getSavedProfileName() {
        return savedProfileName;
    }

    private void initializeUI() {
        setLayout(new BorderLayout());

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        JLabel profileNameLabel = new JLabel("Profile Name:");
        panel.add(profileNameLabel, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        profileNameField = new JTextField();
        profileNameField.setToolTipText("The name AWS CLI/SDK tools will reference via --profile or AWS_PROFILE");
        profileNameLabel.setLabelFor(profileNameField);
        panel.add(profileNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        JLabel guiNameLabel = new JLabel("GUI Name:");
        panel.add(guiNameLabel, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        guiNameField = new JTextField();
        guiNameField.setToolTipText("The name displayed by the SAML login configuration");
        guiNameLabel.setLabelFor(guiNameField);
        panel.add(guiNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Identity Provider:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        samlProviderCombo = new JComboBox<>(configManager.getIdpProviders().toArray(new String[0]));
        samlProviderCombo.setToolTipText("Which configured identity provider handles login for this profile");
        panel.add(samlProviderCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Account Number:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        accountNumberField = new JTextField();
        accountNumberField.setToolTipText("The 12-digit AWS account number to assume a role in");
        panel.add(accountNumberField, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("IAM Role Name:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        iamRoleField = new JTextField();
        iamRoleField.setToolTipText("The IAM role name (not the full ARN) to assume via the SAML response");
        panel.add(iamRoleField, gbc);

        gbc.gridx = 0; gbc.gridy = 5; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Session Duration:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        panel.add(buildDurationRow(), gbc);

        gbc.gridx = 0; gbc.gridy = 6; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        JLabel regionLabel = new JLabel("Region (optional):");
        panel.add(regionLabel, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        regionField = new JTextField();
        regionField.setToolTipText("e.g. eu-west-1. Used for this profile's AWS calls, its ~/.aws/config region, and "
            + "where Open Console lands. Blank uses the global region.");
        regionLabel.setLabelFor(regionField);
        panel.add(regionField, gbc);

        gbc.gridx = 0; gbc.gridy = 7; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        JLabel consoleServiceLabel = new JLabel("Open Console to:");
        panel.add(consoleServiceLabel, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        java.util.List<Object> choices = new java.util.ArrayList<>();
        choices.add(CONSOLE_HOME);
        choices.addAll(ConsoleService.ALL);
        consoleServiceCombo = new JComboBox<>(choices.toArray());
        consoleServiceCombo.setMaximumRowCount(16);
        consoleServiceCombo.setToolTipText("The AWS console page Open Console lands on, in this profile's region if it has one. "
            + "Type a letter to jump to a service.");
        consoleServiceLabel.setLabelFor(consoleServiceCombo);
        panel.add(consoleServiceCombo, gbc);

        add(panel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setMnemonic(KeyEvent.VK_C);
        JButton saveButton = new JButton("Save");
        saveButton.setMnemonic(KeyEvent.VK_S);
        cancelButton.addActionListener(e -> setVisible(false));
        saveButton.addActionListener(e -> onSave());
        buttonPanel.add(cancelButton);
        buttonPanel.add(saveButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    /**
     * Per-profile session duration (#219): "Use global default" (no override saved) or a value in
     * minutes, bounded like the global setting in Configuration.
     */
    private JComponent buildDurationRow() {
        int minMinutes = configManager.getMinSessionDuration() / 60;
        int maxMinutes = configManager.getMaxSessionDuration() / 60;
        int defaultMinutes = configManager.getDefaultSessionDuration() / 60;
        durationSpinner = new JSpinner(new SpinnerNumberModel(clamp(defaultMinutes, minMinutes, maxMinutes), minMinutes, maxMinutes, 15));
        durationSpinner.setToolTipText("How long this profile's credentials stay valid, in minutes. AWS caps it at the "
            + "role's maximum session duration in IAM.");
        useDefaultDurationCheckBox = new JCheckBox("Use global default (" + defaultMinutes + " min)", true);
        useDefaultDurationCheckBox.setMnemonic(KeyEvent.VK_U);
        useDefaultDurationCheckBox.setToolTipText("Follow the default in File → Configuration → Session, including later changes to it");
        useDefaultDurationCheckBox.addActionListener(e -> durationSpinner.setEnabled(!useDefaultDurationCheckBox.isSelected()));
        durationSpinner.setEnabled(false);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(useDefaultDurationCheckBox);
        row.add(durationSpinner);
        row.add(new JLabel("minutes"));
        return row;
    }

    private static Integer parseSeconds(String value) {
        try {
            int seconds = value == null ? 0 : Integer.parseInt(value.trim());
            return seconds > 0 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void loadExistingProfile(String profileName) {
        profileNameField.setText(profileName);

        Profile.Section section = configManager.getProfile(profileName);
        if (section == null) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String key : section.keySet()) {
            fields.put(key, section.get(key));
        }
        loadFields(fields, false);
    }

    private void loadFields(Map<String, String> section, boolean asCopy) {
        String guiName = section.getOrDefault("guiname", "");
        guiNameField.setText(asCopy && !guiName.isBlank() ? guiName + " (copy)" : guiName);
        accountNumberField.setText(section.getOrDefault("accountnumber", ""));
        iamRoleField.setText(section.getOrDefault("iamrole", ""));
        samlProviderCombo.setSelectedItem(section.getOrDefault("samlprovider", ""));
        regionField.setText(section.getOrDefault("awsregion", "").trim());
        ConsoleService service = ConsoleService.find(section.get("consoleservice"));
        consoleServiceCombo.setSelectedItem(service != null ? service : CONSOLE_HOME);

        Integer own = parseSeconds(section.get("sessionduration"));
        if (own != null) {
            SpinnerNumberModel model = (SpinnerNumberModel) durationSpinner.getModel();
            // An out-of-range legacy value (e.g. hand-edited) is shown clamped; saving stores the clamped value.
            durationSpinner.setValue(clamp(own / 60, (Integer) model.getMinimum(), (Integer) model.getMaximum()));
            useDefaultDurationCheckBox.setSelected(false);
            durationSpinner.setEnabled(true);
        }
    }

    private void onSave() {
        String profileName = profileNameField.getText().trim();
        String guiName = guiNameField.getText().trim();
        String samlProvider = (String) samlProviderCombo.getSelectedItem();
        String accountNumber = accountNumberField.getText().trim();
        String iamRole = iamRoleField.getText().trim();

        if (profileName.isEmpty() || guiName.isEmpty() || samlProvider == null || accountNumber.isEmpty() || iamRole.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "All fields are required.",
                "Missing Fields",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        String regionText = regionField.getText().trim();
        if (!regionText.isEmpty() && !ConsoleDestination.isValidRegion(regionText)) {
            JOptionPane.showMessageDialog(this,
                "\"" + regionText + "\" isn't an AWS region id (for example eu-west-1).",
                "Invalid Region", JOptionPane.WARNING_MESSAGE);
            return;
        }

        boolean isRename = originalProfileName != null && !originalProfileName.equals(profileName);
        boolean isNewName = originalProfileName == null || isRename;
        if (isNewName && configManager.getAvailableProfiles().contains(profileName)) {
            JOptionPane.showMessageDialog(this,
                "A profile named \"" + profileName + "\" already exists.",
                "Duplicate Profile Name",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        if (template != null) {
            // Carry over keys this dialog doesn't edit; the ones it does are set just below.
            template.forEach((key, value) -> {
                if (!Set.of("guiname", "accountnumber", "iamrole", "samlprovider", "sessionduration", "awsregion",
                        "consoleservice", "consoledestination").contains(key)) {
                    fields.put(key, value);
                }
            });
        }
        fields.put("guiname", guiName);
        fields.put("accountnumber", accountNumber);
        fields.put("iamrole", iamRole);
        fields.put("samlprovider", samlProvider);
        Set<String> removeKeys = new java.util.HashSet<>();
        if (useDefaultDurationCheckBox.isSelected()) {
            removeKeys.add("sessionduration");
        } else {
            fields.put("sessionduration", String.valueOf((Integer) durationSpinner.getValue() * 60));
        }
        String region = regionField.getText().trim();
        if (region.isEmpty()) {
            removeKeys.add("awsregion");
        } else {
            fields.put("awsregion", region);
        }
        // The free-form URL key from before #239 never shipped in a release; drop any leftover.
        removeKeys.add("consoledestination");
        if (consoleServiceCombo.getSelectedItem() instanceof ConsoleService service) {
            fields.put("consoleservice", service.id());
        } else {
            removeKeys.add("consoleservice");
        }

        try {
            if (isRename) {
                configManager.deleteProfile(originalProfileName);
            }
            configManager.saveProfile(profileName, fields, removeKeys);
            saved = true;
            savedProfileName = profileName;
            setVisible(false);
        } catch (Exception ex) {
            logger.error("Failed to save profile: {}", profileName, ex);
            JOptionPane.showMessageDialog(this,
                "Failed to save profile: " + ex.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }
}
