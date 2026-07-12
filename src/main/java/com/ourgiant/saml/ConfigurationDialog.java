package com.ourgiant.saml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

/**
 * Configuration dialog for setting application options.
 */
public class ConfigurationDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationDialog.class);

    private static final String[] BROWSERS = {"chrome", "firefox"};

    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final PasswordManager passwordManager;
    private JSpinner durationSpinner;
    private JCheckBox storePasswordCheckBox;
    private JButton forgetPasswordButton;
    private JSpinner passwordExpirationSpinner;
    private JComboBox<String> themeComboBox;
    private JComboBox<String> browserComboBox;
    private JCheckBox useFastPassCheckBox;
    private JCheckBox trayNotificationsCheckBox;
    private JCheckBox startMinimizedCheckBox;
    private JButton saveButton;
    private JButton cancelButton;

    public ConfigurationDialog(Frame parent, ConfigManager configManager, DatabaseManager databaseManager, PasswordManager passwordManager) {
        super(parent, "Configuration", true);
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.passwordManager = passwordManager;

        initializeUI();
        loadCurrentSettings();
        pack();
        setMinimumSize(getSize());
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
        outerGbc.gridy = 3; outerPanel.add(buildBrowserSection(), outerGbc);
        outerGbc.gridy = 4; outerPanel.add(buildAuthSection(), outerGbc);
        outerGbc.gridy = 5; outerPanel.add(buildNotificationsSection(), outerGbc);
        outerGbc.gridy = 6; outerPanel.add(buildStartupSection(), outerGbc);

        add(outerPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        saveButton = new JButton("Save");
        saveButton.setMnemonic(KeyEvent.VK_S);
        cancelButton = new JButton("Cancel");
        cancelButton.setMnemonic(KeyEvent.VK_C);
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
        durationSpinner.setToolTipText("How long requested AWS credentials remain valid before they expire");
        panel.add(durationSpinner, gbc);

        return panel;
    }

    private JPanel buildPasswordSection() {
        JPanel panel = titledPanel("Password Storage");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        storePasswordCheckBox = new JCheckBox("Store and use Okta password");
        storePasswordCheckBox.setMnemonic(KeyEvent.VK_T);
        storePasswordCheckBox.addActionListener(e -> updatePasswordExpirationEnabled());
        storePasswordCheckBox.setToolTipText("Securely cache your Okta password locally so you're not prompted for it every login");
        panel.add(storePasswordCheckBox, gbc);
        gbc.gridwidth = 1;

        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Expiration (minutes):"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        passwordExpirationSpinner = new JSpinner(new SpinnerNumberModel(
            databaseManager.getPasswordExpirationMinutes(), 15, 10080, 60
        ));
        passwordExpirationSpinner.setEnabled(false);
        passwordExpirationSpinner.setToolTipText("How long the stored password stays cached before you must re-enter it");
        panel.add(passwordExpirationSpinner, gbc);
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;

        gbc.gridx = 1; gbc.gridy = 2; gbc.anchor = GridBagConstraints.EAST;
        forgetPasswordButton = new JButton("Forget Password");
        forgetPasswordButton.setMnemonic(KeyEvent.VK_G);
        forgetPasswordButton.setEnabled(false);
        forgetPasswordButton.addActionListener(e -> forgetStoredPassword());
        forgetPasswordButton.setToolTipText("Clear the securely stored password immediately");
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
        themeComboBox.setToolTipText("Application color theme");
        panel.add(themeComboBox, gbc);

        return panel;
    }

    private JPanel buildBrowserSection() {
        JPanel panel = titledPanel("Browser");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Browser:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        browserComboBox = new JComboBox<>(BROWSERS);
        browserComboBox.setToolTipText("Browser used to drive the SAML login flow");
        panel.add(browserComboBox, gbc);

        return panel;
    }

    private JPanel buildAuthSection() {
        JPanel panel = titledPanel("Authentication");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        useFastPassCheckBox = new JCheckBox("Use Okta FastPass");
        useFastPassCheckBox.setMnemonic(KeyEvent.VK_U);
        useFastPassCheckBox.setToolTipText("Attempt Okta FastPass (device-based) login before falling back to password entry");
        panel.add(useFastPassCheckBox, gbc);

        return panel;
    }

    private JPanel buildNotificationsSection() {
        JPanel panel = titledPanel("Notifications");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        trayNotificationsCheckBox = new JCheckBox("Show tray notification before credentials expire");
        trayNotificationsCheckBox.setMnemonic(KeyEvent.VK_Y);
        boolean traySupported = SystemTray.isSupported();
        trayNotificationsCheckBox.setEnabled(traySupported);
        trayNotificationsCheckBox.setToolTipText(traySupported
            ? "Show a system tray notification a few minutes before a profile's AWS credentials expire"
            : "System tray is not available on this platform/environment");
        panel.add(trayNotificationsCheckBox, gbc);

        return panel;
    }

    private JPanel buildStartupSection() {
        JPanel panel = titledPanel("Startup");
        GridBagConstraints gbc = sectionGbc();

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        startMinimizedCheckBox = new JCheckBox("Start minimized to tray");
        startMinimizedCheckBox.setMnemonic(KeyEvent.VK_M);
        boolean traySupported = SystemTray.isSupported();
        startMinimizedCheckBox.setEnabled(traySupported);
        startMinimizedCheckBox.setToolTipText(traySupported
            ? "Skip showing the main window on launch; the app starts hidden in the system tray"
            : "System tray is not available on this platform/environment");
        panel.add(startMinimizedCheckBox, gbc);

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

        browserComboBox.setSelectedItem(configManager.getBrowserType());

        useFastPassCheckBox.setSelected(databaseManager.getFastPassEnabled());

        trayNotificationsCheckBox.setSelected(databaseManager.getTrayNotificationsEnabled());

        startMinimizedCheckBox.setSelected(databaseManager.getStartMinimizedToTray());

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

                String selectedBrowser = (String) browserComboBox.getSelectedItem();
                databaseManager.setBrowser(selectedBrowser);

                boolean useFastPass = useFastPassCheckBox.isSelected();
                databaseManager.setFastPassEnabled(useFastPass);

                boolean trayNotificationsEnabled = trayNotificationsCheckBox.isSelected();
                databaseManager.setTrayNotificationsEnabled(trayNotificationsEnabled);

                boolean startMinimized = startMinimizedCheckBox.isSelected();
                databaseManager.setStartMinimizedToTray(startMinimized);

                logger.info("Configuration saved: session_duration = {} seconds, store_password = {}, password_expiration = {} minutes, theme = {}, browser = {}, use_fastpass = {}, tray_notifications = {}, start_minimized_to_tray = {}",
                    durationSeconds, storePassword, passwordExpirationMinutes, selectedTheme, selectedBrowser, useFastPass, trayNotificationsEnabled, startMinimized);

                // Apply theme immediately — ThemeManager refreshes all open windows,
                // including this dialog, so it re-themes live.
                if (!ThemeManager.applyTheme(selectedTheme)) {
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