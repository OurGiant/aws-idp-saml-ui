package com.ourgiant.saml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modal dialog shown on first launch when no config file exists.
 * Collects global settings, one IDP, and one AWS profile, then writes ~/.aws/samlsts.
 */
public class FirstRunSetupDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(FirstRunSetupDialog.class);

    private static final String[] BROWSERS = {"chrome", "firefox"};

    private static final String[] AWS_REGIONS = {
        "us-east-1", "us-east-2", "us-west-1", "us-west-2",
        "us-gov-east-1", "us-gov-west-1"
    };

    private record IdpPreset(String sectionSuffix, String loginPage, String loginTitle) {}

    private static final Map<String, IdpPreset> IDP_PRESETS = new LinkedHashMap<>();
    static {
        IDP_PRESETS.put("Okta",         new IdpPreset("Okta",     "https://your-org.okta.com/app/amazon_aws/your-app-id/sso/saml", "Okta"));
        IDP_PRESETS.put("Azure AD",     new IdpPreset("AzureAD",  "https://login.microsoftonline.com/your-tenant-id/saml2",         "Sign in to your account"));
        IDP_PRESETS.put("ADFS",         new IdpPreset("ADFS",     "https://your-adfs-server/adfs/ls/IdpInitiatedSignOn.aspx",        "Sign In"));
        IDP_PRESETS.put("Ping Identity",new IdpPreset("Ping",     "https://your-ping-server/idp/startSSO.ping",                      "Sign On"));
        IDP_PRESETS.put("OneLogin",     new IdpPreset("OneLogin", "https://your-subdomain.onelogin.com/trust/saml2/http-post/sso/your-app-id", "OneLogin"));
    }

    // Global fields
    private JComboBox<String> browserCombo;
    private JTextField usernameField;
    private JComboBox<String> regionCombo;
    private JSpinner sessionDurationSpinner;

    // IDP fields
    private JComboBox<String> idpTypeCombo;
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
        JButton saveButton = new JButton("Save & Launch");
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
        regionCombo = new JComboBox<>(AWS_REGIONS);
        panel.add(regionCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Session Duration (minutes):"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        sessionDurationSpinner = new JSpinner(new SpinnerNumberModel(240, 15, 720, 15));
        panel.add(sessionDurationSpinner, gbc);

        return panel;
    }

    private JPanel buildIdpSection() {
        JPanel panel = titledPanel("Identity Provider");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("IDP Type:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        idpTypeCombo = new JComboBox<>(IDP_PRESETS.keySet().toArray(new String[0]));
        idpTypeCombo.addActionListener(e -> applyIdpPreset());
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
        panel.add(loginPageField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        panel.add(new JLabel("Login Page Title:"), gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        loginTitleField = new JTextField();
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
        String selected = (String) idpTypeCombo.getSelectedItem();
        IdpPreset preset = IDP_PRESETS.get(selected);
        if (preset == null) return;
        idpNameField.setText(preset.sectionSuffix());
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

        String sectionName = "Fed-" + idpName;
        int sessionSeconds = (Integer) sessionDurationSpinner.getValue() * 60;

        Map<String, Map<String, String>> sections = new LinkedHashMap<>();

        Map<String, String> global = new LinkedHashMap<>();
        global.put("browser", (String) browserCombo.getSelectedItem());
        global.put("username", username);
        global.put("awsregion", (String) regionCombo.getSelectedItem());
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
