package com.ourgiant.saml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Configuration dialog for setting application options.
 */
public class ConfigurationDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationDialog.class);

    private final DatabaseManager databaseManager;
    private final PasswordManager passwordManager;
    private JSpinner durationSpinner;
    private JCheckBox storePasswordCheckBox;
    private JButton forgetPasswordButton;
    private JSpinner passwordExpirationSpinner;
    private JComboBox<String> themeComboBox;
    private JCheckBox useFastPassCheckBox;
    private JButton saveButton;
    private JButton cancelButton;

    public ConfigurationDialog(Frame parent, DatabaseManager databaseManager, PasswordManager passwordManager) {
        super(parent, "Configuration", true);
        this.databaseManager = databaseManager;
        this.passwordManager = passwordManager;

        initializeUI();
        loadCurrentSettings();
        pack();
        setLocationRelativeTo(parent);
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

        outerGbc.gridy = 0; outerPanel.add(buildSessionSection(), outerGbc);
        outerGbc.gridy = 1; outerPanel.add(buildPasswordSection(), outerGbc);
        outerGbc.gridy = 2; outerPanel.add(buildAppearanceSection(), outerGbc);
        outerGbc.gridy = 3; outerPanel.add(buildAuthSection(), outerGbc);

        add(outerPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        saveButton = new JButton("Save");
        cancelButton = new JButton("Cancel");
        saveButton.addActionListener(new SaveActionListener());
        cancelButton.addActionListener(e -> setVisible(false));
        buttonPanel.add(cancelButton);
        buttonPanel.add(saveButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private JPanel buildSessionSection() {
        JPanel panel = titledPanel("Session");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Duration (minutes):"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        durationSpinner = new JSpinner(new SpinnerNumberModel(
            databaseManager.getSessionDuration() / 60,
            databaseManager.getMinDuration() / 60,
            databaseManager.getMaxDuration() / 60,
            15
        ));
        panel.add(durationSpinner, gbc);

        return panel;
    }

    private JPanel buildPasswordSection() {
        JPanel panel = titledPanel("Password Storage");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        storePasswordCheckBox = new JCheckBox("Store and use Okta password");
        storePasswordCheckBox.addActionListener(e -> updatePasswordExpirationEnabled());
        panel.add(storePasswordCheckBox, gbc);
        gbc.gridwidth = 1;

        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Expiration (minutes):"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        passwordExpirationSpinner = new JSpinner(new SpinnerNumberModel(
            databaseManager.getPasswordExpirationMinutes(), 15, 10080, 60
        ));
        passwordExpirationSpinner.setEnabled(false);
        panel.add(passwordExpirationSpinner, gbc);
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;

        gbc.gridx = 1; gbc.gridy = 2; gbc.anchor = GridBagConstraints.EAST;
        forgetPasswordButton = new JButton("Forget Password");
        forgetPasswordButton.setEnabled(false);
        forgetPasswordButton.addActionListener(e -> forgetStoredPassword());
        panel.add(forgetPasswordButton, gbc);

        return panel;
    }

    private JPanel buildAppearanceSection() {
        JPanel panel = titledPanel("Appearance");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Theme:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        themeComboBox = new JComboBox<>();
        for (String theme : ThemeManager.getAvailableThemeNames()) {
            themeComboBox.addItem(theme);
        }
        panel.add(themeComboBox, gbc);

        return panel;
    }

    private JPanel buildAuthSection() {
        JPanel panel = titledPanel("Authentication");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        useFastPassCheckBox = new JCheckBox("Use Okta FastPass");
        panel.add(useFastPassCheckBox, gbc);

        return panel;
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

    private void updatePasswordExpirationEnabled() {
        passwordExpirationSpinner.setEnabled(storePasswordCheckBox.isSelected());
        boolean hasStoredPassword = !isEmpty(databaseManager.getConfig("okta_password"));
        forgetPasswordButton.setEnabled(hasStoredPassword);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private void forgetStoredPassword() {
        int confirm = JOptionPane.showConfirmDialog(
            this,
            "Clear the stored Okta password? You will be prompted to enter it on next login.",
            "Forget Password",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (confirm == JOptionPane.YES_OPTION) {
            passwordManager.clearPassword();
            forgetPasswordButton.setEnabled(false);
            logger.info("Stored password cleared by user");
            JOptionPane.showMessageDialog(this, "Stored password cleared.", "Done", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void loadCurrentSettings() {
        int currentDurationMinutes = databaseManager.getSessionDuration() / 60;
        durationSpinner.setValue(currentDurationMinutes);

        storePasswordCheckBox.setSelected(databaseManager.getConfig("store_password_enabled") != null &&
                                          databaseManager.getConfig("store_password_enabled").equalsIgnoreCase("true"));
        passwordExpirationSpinner.setValue(databaseManager.getPasswordExpirationMinutes());

        themeComboBox.setSelectedItem(databaseManager.getTheme());

        useFastPassCheckBox.setSelected(databaseManager.getFastPassEnabled());

        updatePasswordExpirationEnabled();
    }

    private class SaveActionListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            try {
                int durationMinutes = (Integer) durationSpinner.getValue();
                int durationSeconds = durationMinutes * 60;
                databaseManager.setSessionDuration(durationSeconds);

                int passwordExpirationMinutes = (Integer) passwordExpirationSpinner.getValue();
                databaseManager.setPasswordExpirationMinutes(passwordExpirationMinutes);

                boolean storePassword = storePasswordCheckBox.isSelected();
                passwordManager.setPasswordStorageEnabled(storePassword);

                String selectedTheme = (String) themeComboBox.getSelectedItem();
                databaseManager.setTheme(selectedTheme);

                boolean useFastPass = useFastPassCheckBox.isSelected();
                databaseManager.setFastPassEnabled(useFastPass);

                logger.info("Configuration saved: session_duration = {} seconds, store_password = {}, password_expiration = {} minutes, theme = {}, use_fastpass = {}",
                    durationSeconds, storePassword, passwordExpirationMinutes, selectedTheme, useFastPass);

                // Apply theme immediately
                if (ThemeManager.applyTheme(selectedTheme)) {
                    SwingUtilities.updateComponentTreeUI(getParent());
                } else {
                    logger.warn("Failed to apply theme immediately: {}", selectedTheme);
                }

                JOptionPane.showMessageDialog(ConfigurationDialog.this,
                    "Configuration saved successfully!",
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE);

                setVisible(false);
            } catch (Exception ex) {
                logger.error("Failed to save configuration", ex);
                JOptionPane.showMessageDialog(ConfigurationDialog.this,
                    "Failed to save configuration: " + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}