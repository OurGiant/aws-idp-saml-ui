package com.ourgiant.saml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Main Swing application for AWS SAML authentication
 */
public class SwingMain extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(SwingMain.class);

    private JComboBox<String> profileComboBox;
    private JCheckBox showBrowserCheckBox;
    private JButton requestCredentialsButton;
    private JButton showEncryptedButton;
    private JButton showCredentialsButton;

    private DefaultTableModel tokenStatusTableModel;
    private JTable tokenStatusTable;
    private JLabel lastRefreshedLabel;
    private JLabel statusLabel;
    private Timer statusRefreshTimer;

    private ConfigManager configManager;
    private CredentialManager credentialManager;
    private TokenStateManager tokenStateManager;
    private DatabaseManager databaseManager;
    private PasswordManager passwordManager;

    public SwingMain() {
        configManager = new ConfigManager();
        credentialManager = new CredentialManager();
        tokenStateManager = new TokenStateManager();
        databaseManager = new DatabaseManager();
        passwordManager = new PasswordManager(databaseManager);

        // Set theme
        setLookAndFeel();

        initializeUI();
        loadProfiles();
        refreshStatusTable();
        startStatusPolling();
    }

    private void setLookAndFeel() {
        String themeName = databaseManager.getTheme();
        if (!ThemeManager.applyTheme(themeName)) {
            // If theme fails to apply, fallback to Flat Dark
            logger.warn("Failed to apply theme: {}, falling back to Flat Dark", themeName);
            ThemeManager.applyTheme("Flat Dark");
            databaseManager.setTheme("Flat Dark");
        }
    }

    private void initializeUI() {
        setTitle("AWS IDP SAML Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        setWindowIcon();
        setJMenuBar(createMenuBar());

        // Profile selection panel
        JPanel profilePanel = new JPanel(new FlowLayout());
        profilePanel.add(new JLabel("Select Profile:"));
        profileComboBox = new JComboBox<>();
        profileComboBox.setPreferredSize(new Dimension(220, 25));
        profileComboBox.addActionListener(e -> updateCredentialButtons());
        profilePanel.add(profileComboBox);

        requestCredentialsButton = new JButton("Request Credentials");
        requestCredentialsButton.addActionListener(new RequestCredentialsListener());
        profilePanel.add(requestCredentialsButton);

        showBrowserCheckBox = new JCheckBox("Show browser");
        showBrowserCheckBox.setSelected(false);
        profilePanel.add(showBrowserCheckBox);

        showEncryptedButton = new JButton("Encrypted");
        showEncryptedButton.addActionListener(e -> showCredentialsDialog(true, false));
        showEncryptedButton.setEnabled(false); // Initially disabled until credentials are available
        profilePanel.add(showEncryptedButton);

        showCredentialsButton = new JButton("Show Credentials");
        showCredentialsButton.addActionListener(e -> showCredentialsDialog(false, true));
        showCredentialsButton.setEnabled(false); // Initially disabled until credentials are available
        profilePanel.add(showCredentialsButton);

        add(profilePanel, BorderLayout.NORTH);

        // Token status panel
        JPanel tokenStatusPanel = new JPanel(new BorderLayout());
        tokenStatusPanel.setBorder(BorderFactory.createTitledBorder("Credential Status"));

        tokenStatusTableModel = new DefaultTableModel(new String[]{"Profile", "Status", "Expires At", "Time Remaining"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        tokenStatusTable = new JTable(tokenStatusTableModel);
        tokenStatusTable.setFillsViewportHeight(true);
        tokenStatusTable.setRowHeight(26);
        tokenStatusTable.getColumnModel().getColumn(1).setCellRenderer(new StatusTableCellRenderer());
        tokenStatusTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int row = tokenStatusTable.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    String profile = (String) tokenStatusTableModel.getValueAt(row, 0);
                    profileComboBox.setSelectedItem(profile);
                }
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(tokenStatusTable);
        tokenStatusPanel.add(tableScrollPane, BorderLayout.CENTER);

        JPanel statusControls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshStatusButton = new JButton("Refresh Status");
        refreshStatusButton.addActionListener(e -> refreshStatusTable());
        statusControls.add(refreshStatusButton);
        lastRefreshedLabel = new JLabel();
        statusControls.add(lastRefreshedLabel);
        tokenStatusPanel.add(statusControls, BorderLayout.SOUTH);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.add(tokenStatusPanel, BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        statusLabel = new JLabel("Ready");
        statusPanel.add(statusLabel);
        add(statusPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");

        JMenuItem configMenuItem = new JMenuItem("Configuration...");
        configMenuItem.addActionListener(e -> showConfigurationDialog());
        fileMenu.add(configMenuItem);

        fileMenu.addSeparator();

        JMenuItem aboutMenuItem = new JMenuItem("About...");
        aboutMenuItem.addActionListener(e -> showAboutDialog());
        fileMenu.add(aboutMenuItem);

        fileMenu.addSeparator();

        JMenuItem exitMenuItem = new JMenuItem("Exit");
        exitMenuItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitMenuItem);

        menuBar.add(fileMenu);
        return menuBar;
    }

    private void showAboutDialog() {
        String version = getClass().getPackage() != null ? getClass().getPackage().getImplementationVersion() : null;
        if (version == null) {
            version = "1.0.8";
        }
        final String currentVersion = version;

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        JLabel nameLabel = new JLabel("AWS IDP SAML UI");
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 16f));
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel versionLabel = new JLabel("Version " + currentVersion);
        versionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel descLabel = new JLabel("AWS SAML authentication client");
        descLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel copyrightLabel = new JLabel("© OurGiant");
        copyrightLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel updateLabel = new JLabel("Checking for updates...");
        updateLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        updateLabel.setForeground(Color.GRAY);

        panel.add(nameLabel);
        panel.add(Box.createVerticalStrut(8));
        panel.add(versionLabel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(descLabel);
        panel.add(Box.createVerticalStrut(8));
        panel.add(copyrightLabel);
        panel.add(Box.createVerticalStrut(8));
        panel.add(updateLabel);

        SwingWorker<String[], Void> versionChecker = new SwingWorker<>() {
            @Override
            protected String[] doInBackground() {
                return fetchLatestRelease();
            }

            @Override
            protected void done() {
                try {
                    String[] release = get();
                    if (release != null) {
                        String latestTag = release[0];
                        String releaseUrl = release[1];
                        String latestVersion = latestTag.startsWith("v") ? latestTag.substring(1) : latestTag;
                        if (isNewerVersion(latestVersion, currentVersion)) {
                            updateLabel.setText("<html><a href=''>Version " + latestVersion + " available — click to download</a></html>");
                            updateLabel.setForeground(new Color(0, 102, 204));
                            updateLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            updateLabel.addMouseListener(new java.awt.event.MouseAdapter() {
                                @Override
                                public void mouseClicked(java.awt.event.MouseEvent e) {
                                    try {
                                        Desktop.getDesktop().browse(new java.net.URI(releaseUrl));
                                    } catch (Exception ex) {
                                        logger.warn("Could not open release URL in browser", ex);
                                    }
                                }
                            });
                        } else {
                            updateLabel.setText("Up to date");
                            updateLabel.setForeground(new Color(0, 128, 0));
                        }
                    } else {
                        updateLabel.setText("Could not check for updates");
                    }
                } catch (Exception e) {
                    updateLabel.setText("Could not check for updates");
                    logger.debug("Version check failed", e);
                }
                panel.revalidate();
                panel.repaint();
            }
        };
        versionChecker.execute();

        JOptionPane.showMessageDialog(this, panel, "About AWS IDP SAML UI", JOptionPane.PLAIN_MESSAGE);
    }

    private String[] fetchLatestRelease() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.github.com/repos/OurGiant/aws-idp-saml-ui/releases/latest"))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "aws-idp-saml-ui")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                String tagName = extractJsonString(body, "tag_name");
                String htmlUrl = extractJsonString(body, "html_url");
                if (tagName != null && htmlUrl != null) {
                    return new String[]{tagName, htmlUrl};
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to fetch latest release from GitHub", e);
        }
        return null;
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    private boolean isNewerVersion(String latest, String current) {
        try {
            String[] latestParts = latest.split("\\.");
            String[] currentParts = current.split("\\.");
            int len = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < len; i++) {
                int l = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                int c = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (l > c) return true;
                if (l < c) return false;
            }
        } catch (NumberFormatException e) {
            logger.debug("Could not compare versions: {} vs {}", latest, current);
        }
        return false;
    }

    private void setWindowIcon() {
        try {
            ImageIcon icon = new ImageIcon(getClass().getResource("/saml_swing_icon_small.png"));
            if (icon.getImage() != null) {
                setIconImage(icon.getImage());
            }
        } catch (Exception ignore) {
            // Icon is optional and may not be available during development
        }
    }

    private void startStatusPolling() {
        statusRefreshTimer = new Timer(30000, e -> refreshStatusTable());
        statusRefreshTimer.setRepeats(true);
        statusRefreshTimer.start();
    }

    private void refreshStatusTable() {
        try {
            tokenStatusTableModel.setRowCount(0);
            Set<String> profileSet = new TreeSet<>();
            profileSet.addAll(configManager.getAvailableProfiles());
            profileSet.addAll(tokenStateManager.getAllExpirations().keySet());
            profileSet.addAll(credentialManager.getAllProfileNames());

            List<TokenStatusRow> rows = new ArrayList<>();
            Instant now = Instant.now();

            for (String profile : profileSet) {
                Instant expiration = tokenStateManager.getExpiration(profile);
                String status;
                String expiresAtText;
                String timeRemaining;

                if (expiration == null) {
                    status = "UNKNOWN";
                    expiresAtText = "N/A";
                    timeRemaining = "Unknown";
                } else if (expiration.isAfter(now)) {
                    status = "VALID";
                    expiresAtText = formatInstant(expiration);
                    timeRemaining = formatDuration(Duration.between(now, expiration));
                } else {
                    status = "EXPIRED";
                    expiresAtText = formatInstant(expiration);
                    timeRemaining = "Expired";
                }

                rows.add(new TokenStatusRow(profile, status, expiresAtText, timeRemaining));
            }

            // Simplified sorting
            rows.sort((a, b) -> {
                int statusOrder = getStatusOrder(a.getStatus()) - getStatusOrder(b.getStatus());
                if (statusOrder != 0) return statusOrder;
                return a.getProfile().compareTo(b.getProfile());
            });

            for (TokenStatusRow row : rows) {
                tokenStatusTableModel.addRow(new Object[]{row.getProfile(), row.getStatus(), row.getExpiresAt(), row.getTimeRemaining()});
            }

            lastRefreshedLabel.setText("Last refreshed: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            statusLabel.setText("Status refreshed.");
        } catch (Exception e) {
            statusLabel.setText("Failed to update status table: " + e.getMessage());
            System.err.println("Status table update failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int getStatusOrder(String status) {
        return switch (status) {
            case "VALID" -> 0;
            case "UNKNOWN" -> 1;
            default -> 2;
        };
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "N/A";
        }
        try {
            logger.debug("Formatting instant: {} (class: {})", instant, instant.getClass());
            LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            logger.debug("Converted to LocalDateTime: {}", localDateTime);
            String formatted = localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            logger.debug("Formatted result: {}", formatted);
            return formatted;
        } catch (Exception e) {
            logger.error("Error formatting instant: {} - {}", instant, e.getMessage(), e);
            return "Invalid Date";
        }
    }

    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%dh %02dm", hours, minutes);
        }
        if (minutes > 0) {
            return String.format("%dm %02ds", minutes, secs);
        }
        return String.format("%02ds", secs);
    }

    private void loadProfiles() {
        try {
            List<String> profiles = configManager.getAvailableProfiles();
            profileComboBox.removeAllItems();
            for (String profile : profiles) {
                profileComboBox.addItem(profile);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Error loading profiles: " + e.getMessage(),
                "Configuration Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private class RequestCredentialsListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            String selectedProfile = (String) profileComboBox.getSelectedItem();
            if (selectedProfile == null) {
                JOptionPane.showMessageDialog(SwingMain.this,
                    "Please select a profile first.",
                    "No Profile Selected",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Disable button during processing
            requestCredentialsButton.setEnabled(false);
            requestCredentialsButton.setText("Requesting...");
            statusLabel.setText("Starting credential request for profile: " + selectedProfile + "...");

            // Run credential request in background thread
            SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    SamlAuthenticator authenticator = new SamlAuthenticator();
                    authenticator.requestCredentials(
                        selectedProfile,
                        databaseManager.getFastPassEnabled(),
                        showBrowserCheckBox.isSelected(),
                        msg -> SwingUtilities.invokeLater(() -> statusLabel.setText(msg))
                    );
                    return null;
                }

                @Override
                protected void done() {
                    requestCredentialsButton.setEnabled(true);
                    requestCredentialsButton.setText("Request Credentials");

                    try {
                        get(); // Check for exceptions
                        refreshStatusTable();
                        updateCredentialButtons();
                        JOptionPane.showMessageDialog(SwingMain.this,
                            "Credentials successfully obtained for profile: " + selectedProfile,
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(SwingMain.this,
                            "Error obtaining credentials: " + ex.getMessage(),
                            "Authentication Error",
                            JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            worker.execute();
        }
    }

    private static class StatusTableCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (column == 1 && value instanceof String status) {
                switch (status) {
                    case "VALID" -> component.setForeground(new Color(0, 128, 0));
                    case "EXPIRED" -> component.setForeground(Color.RED);
                    default -> component.setForeground(Color.GRAY);
                }
            } else {
                component.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            }
            return component;
        }
    }

    private void showConfigurationDialog() {
        ConfigurationDialog dialog = new ConfigurationDialog(this, databaseManager, passwordManager);
        dialog.setVisible(true);
    }

    private void showCredentialsDialog(boolean showEncrypted, boolean showPlaintext) {
        String selectedProfile = (String) profileComboBox.getSelectedItem();
        if (selectedProfile == null) {
            JOptionPane.showMessageDialog(this,
                "Please select a profile first.",
                "No Profile Selected",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        CredentialManager.AwsCredentials credentials = credentialManager.getCredentials(selectedProfile);
        if (credentials == null) {
            JOptionPane.showMessageDialog(this,
                "No credentials found for profile: " + selectedProfile,
                "No Credentials",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        CredentialsDialog dialog = new CredentialsDialog(this, credentials, showEncrypted, showPlaintext);
        dialog.setVisible(true);
    }

    private void updateCredentialButtons() {
        String selectedProfile = (String) profileComboBox.getSelectedItem();
        boolean hasCredentials = selectedProfile != null &&
                                credentialManager.getCredentials(selectedProfile) != null;

        // Check if public key exists for encrypted credentials
        java.nio.file.Path publicKeyPath = java.nio.file.Paths.get(System.getProperty("user.home"), ".aws", "public_key.pem");
        boolean hasPublicKey = java.nio.file.Files.exists(publicKeyPath);

        showEncryptedButton.setEnabled(hasCredentials && hasPublicKey);
        showCredentialsButton.setEnabled(hasCredentials);
    }

    private static class TokenStatusRow {
        private final String profile;
        private final String status;
        private final String expiresAt;
        private final String timeRemaining;

        public TokenStatusRow(String profile, String status, String expiresAt, String timeRemaining) {
            this.profile = profile;
            this.status = status;
            this.expiresAt = expiresAt;
            this.timeRemaining = timeRemaining;
        }

        public String getProfile() { return profile; }
        public String getStatus() { return status; }
        public String getExpiresAt() { return expiresAt; }
        public String getTimeRemaining() { return timeRemaining; }
    }

    public static void main(String[] args) {
        System.setProperty("SE_AVOID_STATS", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Use default look and feel
            }

            if (!ConfigManager.configFileExists()) {
                DatabaseManager.deleteIfExists();
                FirstRunSetupDialog setup = new FirstRunSetupDialog();
                setup.setVisible(true);
                if (!setup.isSetupCompleted()) {
                    System.exit(0);
                }
            }

            new SwingMain().setVisible(true);
        });
    }
}