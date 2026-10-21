package com.ourgiant.saml.gui;

import com.ourgiant.saml.core.ConfigManager;
import com.ourgiant.saml.core.IdpPresets;
import com.ourgiant.saml.core.RegionChoices;
import com.ourgiant.saml.core.SamlAuthenticator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modal dialog shown on first launch when no config file exists.
 * Collects global settings, one IDP, and one AWS profile, then writes ~/.aws/samlsts.
 */
public class FirstRunSetupDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(FirstRunSetupDialog.class);

    private static final String[] BROWSERS = {"chrome", "firefox", "edge"};

    // Global fields
    private JComboBox<String> browserCombo;
    private JTextField usernameField;
    private JComboBox<RegionChoices.Choice> regionCombo;
    private JSpinner sessionDurationSpinner;

    // IDP fields
    private JComboBox<IdpPresets.Preset> idpTypeCombo;
    private JTextField idpNameField;
    private JTextField loginPageField;
    private JTextField loginTitleField;

    // Profile fields
    private JTextField profileNameField;
    private JTextField accountNumberField;
    private JTextField iamRoleField;

    private boolean setupCompleted = false;

    public FirstRunSetupDialog() {
        super((Frame) null, "Initial Setup — AWS IDP SAML Client", true);
        initializeUI();
        pack();
        setMinimumSize(new Dimension(520, getHeight()));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                confirmCancel();
            }
        });
    }

    public boolean isSetupCompleted() {
        return setupCompleted;
    }

    private void initializeUI() {
        setLayout(new BorderLayout());

        JPanel outerPanel = new JPanel(new GridBagLayout());
        outerPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        GridBagConstraints outerGbc = new GridBagConstraints();
        outerGbc.fill = GridBagConstraints.HORIZONTAL;
        outerGbc.weightx = 1.0;
        outerGbc.gridx = 0;
        outerGbc.insets = new Insets(3, 0, 3, 0);

        outerGbc.gridy = 0; outerPanel.add(buildGlobalSection(), outerGbc);
        outerGbc.gridy = 1; outerPanel.add(buildIdpSection(), outerGbc);
        outerGbc.gridy = 2; outerPanel.add(buildProfileSection(), outerGbc);

        add(outerPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setMnemonic(KeyEvent.VK_C);
        JButton saveButton = new JButton("Save & Launch");
        saveButton.setMnemonic(KeyEvent.VK_S);
        cancelButton.addActionListener(e -> confirmCancel());
        saveButton.addActionListener(e -> save());
        buttonPanel.add(cancelButton);
        buttonPanel.add(saveButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private JPanel buildGlobalSection() {
        JPanel panel = titledPanel("Global Settings");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Browser:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        browserCombo = new JComboBox<>(BROWSERS);
        panel.add(browserCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Username:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        usernameField = new JTextField();
        panel.add(usernameField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("AWS Region:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        regionCombo = new JComboBox<>(RegionChoices.ALL.toArray(new RegionChoices.Choice[0]));
        regionCombo.setMaximumRowCount(16);
        regionCombo.setSelectedItem(RegionChoices.find("us-east-1"));
        panel.add(regionCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Session Duration (minutes):"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        sessionDurationSpinner = new JSpinner(new SpinnerNumberModel(240, 15, 720, 15));
        sessionDurationSpinner.setToolTipText("How long requested AWS credentials remain valid before they expire");
        panel.add(sessionDurationSpinner, gbc);

        return panel;
    }

    private JPanel buildIdpSection() {
        JPanel panel = titledPanel("Identity Provider");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("IDP Type:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        idpTypeCombo = new JComboBox<>(IdpPresets.ALL.toArray(new IdpPresets.Preset[0]));
        idpTypeCombo.addActionListener(e -> applyIdpPreset());
        idpTypeCombo.setToolTipText("Prefills the fields below with a starting point for this identity provider");
        panel.add(idpTypeCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("IDP Name:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        idpNameField = new JTextField();
        panel.add(idpNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Login Page URL:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        loginPageField = new JTextField();
        loginPageField.setToolTipText("The SAML-initiated login URL your browser is sent to first");
        panel.add(loginPageField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Login Page Title:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        loginTitleField = new JTextField();
        loginTitleField.setToolTipText("The browser page title used to detect that the login page has loaded");
        panel.add(loginTitleField, gbc);

        applyIdpPreset();
        return panel;
    }

    private JPanel buildProfileSection() {
        JPanel panel = titledPanel("AWS Profile");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Profile Name:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        profileNameField = new JTextField();
        panel.add(profileNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Account Number:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        accountNumberField = new JTextField();
        panel.add(accountNumberField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("IAM Role Name:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        iamRoleField = new JTextField();
        panel.add(iamRoleField, gbc);

        return panel;
    }

    private void applyIdpPreset() {
        IdpPresets.Preset preset = (IdpPresets.Preset) idpTypeCombo.getSelectedItem();
        if (preset == null) return;
        idpNameField.setText(preset.suggestedName());
        loginPageField.setText(preset.loginPage());
        loginTitleField.setText(preset.loginTitle());
    }

    private void save() {
        String username = usernameField.getText().trim();
        String idpName = idpNameField.getText().trim();
        String loginPage = loginPageField.getText().trim();
        String loginTitle = loginTitleField.getText().trim();
        String profileName = profileNameField.getText().trim();
        String accountNumber = accountNumberField.getText().trim();
        String iamRole = iamRoleField.getText().trim();

        if (username.isEmpty() || idpName.isEmpty() || loginPage.isEmpty() || loginTitle.isEmpty()
                || profileName.isEmpty() || accountNumber.isEmpty() || iamRole.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "All fields are required. Please fill in every field before saving.",
                "Missing Fields",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            SamlAuthenticator.validateSecureLoginUrl(loginPage);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this,
                e.getMessage(),
                "Invalid SAML Login URL",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        String sectionName = "Fed-" + idpName;
        int sessionSeconds = (Integer) sessionDurationSpinner.getValue() * 60;

        Map<String, Map<String, String>> sections = new LinkedHashMap<>();

        Map<String, String> global = new LinkedHashMap<>();
        global.put("browser", (String) browserCombo.getSelectedItem());
        global.put("username", username);
        global.put("awsregion", ((RegionChoices.Choice) regionCombo.getSelectedItem()).id());
        global.put("sessionduration", String.valueOf(sessionSeconds));
        global.put("samlprovider", sectionName);
        sections.put("global", global);

        Map<String, String> idp = new LinkedHashMap<>();
        idp.put("loginpage", loginPage);
        idp.put("logintitle", loginTitle);
        sections.put(sectionName, idp);

        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("accountnumber", accountNumber);
        profile.put("iamrole", iamRole);
        profile.put("samlprovider", sectionName);
        sections.put(profileName, profile);

        try {
            ConfigManager configManager = new ConfigManager(false);
            configManager.createConfig(sections);
            logger.info("Initial configuration written successfully");
            setupCompleted = true;
            setVisible(false);
        } catch (Exception ex) {
            logger.error("Failed to write initial configuration", ex);
            JOptionPane.showMessageDialog(this,
                "Failed to write configuration: " + ex.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private void confirmCancel() {
        int choice = JOptionPane.showConfirmDialog(this,
            "No configuration file exists. Cancelling will exit the application.\nCancel setup?",
            "Cancel Setup",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            System.exit(0);
        }
    }

    private static JPanel titledPanel(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        return panel;
    }

    private static GridBagConstraints sectionGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }
}
