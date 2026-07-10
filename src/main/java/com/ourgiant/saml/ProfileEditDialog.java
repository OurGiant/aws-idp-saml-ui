package com.ourgiant.saml;

import org.ini4j.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dialog for adding or editing a single AWS profile in ~/.aws/samlsts.
 */
public class ProfileEditDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(ProfileEditDialog.class);

    private final ConfigManager configManager;
    private final String originalProfileName; // null when adding a new profile

    private JTextField profileNameField;
    private JComboBox<String> samlProviderCombo;
    private JTextField accountNumberField;
    private JTextField iamRoleField;

    private boolean saved = false;

    public ProfileEditDialog(Frame parent, ConfigManager configManager, String profileName) {
        super(parent, profileName == null ? "Add Profile" : "Edit Profile", true);
        this.configManager = configManager;
        this.originalProfileName = profileName;

        initializeUI();
        if (profileName != null) {
            loadExistingProfile(profileName);
        }
        pack();
        setMinimumSize(new Dimension(380, getHeight()));
        setLocationRelativeTo(parent);
    }

    public boolean isSaved() {
        return saved;
    }

    private void initializeUI() {
        setLayout(new BorderLayout());

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Profile Name:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        profileNameField = new JTextField();
        panel.add(profileNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Identity Provider:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        samlProviderCombo = new JComboBox<>(configManager.getIdpProviders().toArray(new String[0]));
        panel.add(samlProviderCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Account Number:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        accountNumberField = new JTextField();
        panel.add(accountNumberField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("IAM Role Name:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        iamRoleField = new JTextField();
        panel.add(iamRoleField, gbc);

        add(panel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancelButton = new JButton("Cancel");
        JButton saveButton = new JButton("Save");
        cancelButton.addActionListener(e -> setVisible(false));
        saveButton.addActionListener(e -> onSave());
        buttonPanel.add(cancelButton);
        buttonPanel.add(saveButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void loadExistingProfile(String profileName) {
        profileNameField.setText(profileName);

        Profile.Section section = configManager.getProfile(profileName);
        if (section == null) {
            return;
        }
        accountNumberField.setText(section.get("accountnumber", ""));
        iamRoleField.setText(section.get("iamrole", ""));
        samlProviderCombo.setSelectedItem(section.get("samlprovider", ""));
    }

    private void onSave() {
        String profileName = profileNameField.getText().trim();
        String samlProvider = (String) samlProviderCombo.getSelectedItem();
        String accountNumber = accountNumberField.getText().trim();
        String iamRole = iamRoleField.getText().trim();

        if (profileName.isEmpty() || samlProvider == null || accountNumber.isEmpty() || iamRole.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "All fields are required.",
                "Missing Fields",
                JOptionPane.WARNING_MESSAGE);
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
        fields.put("accountnumber", accountNumber);
        fields.put("iamrole", iamRole);
        fields.put("samlprovider", samlProvider);

        try {
            if (isRename) {
                configManager.deleteProfile(originalProfileName);
            }
            configManager.saveProfile(profileName, fields);
            saved = true;
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
