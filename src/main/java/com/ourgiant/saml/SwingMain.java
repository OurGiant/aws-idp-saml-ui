package com.ourgiant.saml;

import com.formdev.flatlaf.FlatLaf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Main Swing application for AWS SAML authentication
 */
public class SwingMain extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(SwingMain.class);
    private static final Duration EXPIRY_WARNING_THRESHOLD = Duration.ofMinutes(5);

    private JComboBox<String> profileComboBox;
    private JCheckBox showBrowserCheckBox;
    private JButton requestCredentialsButton;
    private JButton showEncryptedButton;
    private JButton showCredentialsButton;
    private JButton openConsoleButton;

    private DefaultTableModel tokenStatusTableModel;
    private JTable tokenStatusTable;
    private JLabel lastRefreshedLabel;
    private JLabel statusLabel;
    private JProgressBar loginProgressBar;
    private Timer statusRefreshTimer;
    private volatile boolean credentialRequestInProgress = false;
    private boolean loadingProfiles = false;

    private TrayIcon trayIcon;
    private final Map<String, Instant> lastNotifiedExpiration = new HashMap<>();

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
        initializeSystemTray();

        // Profile selection panel
        JPanel profilePanel = new JPanel(new FlowLayout());
        JLabel selectProfileLabel = new JLabel("Select Profile:");
        profilePanel.add(selectProfileLabel);
        profileComboBox = new JComboBox<>();
        profileComboBox.setPreferredSize(new Dimension(220, 25));
        profileComboBox.addActionListener(e -> {
            updateCredentialButtons();
            saveLastUsedProfile();
        });
        profileComboBox.setToolTipText("The AWS profile to authenticate and fetch credentials for");
        selectProfileLabel.setLabelFor(profileComboBox);
        profilePanel.add(profileComboBox);

        requestCredentialsButton = new JButton("Request Credentials");
        requestCredentialsButton.setMnemonic(KeyEvent.VK_R);
        requestCredentialsButton.addActionListener(new RequestCredentialsListener());
        requestCredentialsButton.setToolTipText("Launch browser login and fetch AWS credentials for the selected profile");
        profilePanel.add(requestCredentialsButton);

        showBrowserCheckBox = new JCheckBox("Show browser");
        showBrowserCheckBox.setMnemonic(KeyEvent.VK_B);
        showBrowserCheckBox.setSelected(false);
        showBrowserCheckBox.setToolTipText("Show the browser window during login instead of running it headless");
        profilePanel.add(showBrowserCheckBox);

        showEncryptedButton = new JButton("Encrypted");
        showEncryptedButton.setMnemonic(KeyEvent.VK_N);
        showEncryptedButton.addActionListener(e -> showCredentialsDialog(true, false));
        showEncryptedButton.setEnabled(false); // Initially disabled until credentials are available
        showEncryptedButton.setToolTipText("View encrypted credentials for use with deployment tools");
        profilePanel.add(showEncryptedButton);

        showCredentialsButton = new JButton("Show Credentials");
        showCredentialsButton.setMnemonic(KeyEvent.VK_C);
        showCredentialsButton.addActionListener(e -> showCredentialsDialog(false, true));
        showCredentialsButton.setEnabled(false); // Initially disabled until credentials are available
        showCredentialsButton.setToolTipText("View plaintext AWS credentials for the selected profile");
        profilePanel.add(showCredentialsButton);

        openConsoleButton = new JButton("Open Console");
        openConsoleButton.setMnemonic(KeyEvent.VK_O);
        openConsoleButton.addActionListener(e -> openAwsConsole());
        openConsoleButton.setEnabled(false); // Initially disabled until credentials are available
        openConsoleButton.setToolTipText("Open the AWS Management Console in your browser using the selected profile's credentials");
        profilePanel.add(openConsoleButton);

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
        tokenStatusTable.setToolTipText("Click a row to select that profile above, or right-click for actions");
        tokenStatusTable.getColumnModel().getColumn(1).setCellRenderer(new StatusTableCellRenderer());
        JPopupMenu tableContextMenu = createTableContextMenu();
        tokenStatusTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int row = tokenStatusTable.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    String profile = (String) tokenStatusTableModel.getValueAt(row, 0);
                    profileComboBox.setSelectedItem(profile);
                }
            }

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                maybeShowContextMenu(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                maybeShowContextMenu(e);
            }

            private void maybeShowContextMenu(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = tokenStatusTable.rowAtPoint(e.getPoint());
                if (row < 0) {
                    return;
                }
                tokenStatusTable.setRowSelectionInterval(row, row);
                String profile = (String) tokenStatusTableModel.getValueAt(row, 0);
                profileComboBox.setSelectedItem(profile);
                tableContextMenu.show(tokenStatusTable, e.getX(), e.getY());
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(tokenStatusTable);
        tokenStatusPanel.add(tableScrollPane, BorderLayout.CENTER);

        JPanel statusControls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshStatusButton = new JButton("Refresh Status");
        refreshStatusButton.setMnemonic(KeyEvent.VK_U);
        refreshStatusButton.addActionListener(e -> refreshStatusTable());
        refreshStatusButton.setToolTipText("Recheck credential expiration status for all profiles");
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
        loginProgressBar = new JProgressBar();
        loginProgressBar.setPreferredSize(new Dimension(120, 16));
        loginProgressBar.setIndeterminate(true);
        loginProgressBar.setVisible(false);
        statusPanel.add(loginProgressBar);
        add(statusPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem manageProfilesMenuItem = new JMenuItem("Manage Profiles...");
        manageProfilesMenuItem.setMnemonic(KeyEvent.VK_M);
        manageProfilesMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK));
        manageProfilesMenuItem.addActionListener(e -> showProfileManagerDialog());
        fileMenu.add(manageProfilesMenuItem);

        JMenuItem configMenuItem = new JMenuItem("Configuration...");
        configMenuItem.setMnemonic(KeyEvent.VK_C);
        configMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, InputEvent.CTRL_DOWN_MASK));
        configMenuItem.addActionListener(e -> showConfigurationDialog());
        fileMenu.add(configMenuItem);

        fileMenu.addSeparator();

        JMenuItem aboutMenuItem = new JMenuItem("About...");
        aboutMenuItem.setMnemonic(KeyEvent.VK_A);
        aboutMenuItem.addActionListener(e -> showAboutDialog());
        fileMenu.add(aboutMenuItem);

        fileMenu.addSeparator();

        JMenuItem exitMenuItem = new JMenuItem("Exit");
        exitMenuItem.setMnemonic(KeyEvent.VK_X);
        exitMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        exitMenuItem.addActionListener(e -> exitApplication());
        fileMenu.add(exitMenuItem);

        menuBar.add(fileMenu);
        return menuBar;
    }

    private void showAboutDialog() {
        final String currentVersion = resolveCurrentVersion();

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

    private String resolveCurrentVersion() {
        String version = getClass().getPackage() != null ? getClass().getPackage().getImplementationVersion() : null;
        if (version == null) {
            version = readVersionFromPropertiesResource();
        }
        return version != null ? version : "unknown";
    }

    private String readVersionFromPropertiesResource() {
        try (InputStream in = getClass().getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                return props.getProperty("version");
            }
        } catch (IOException e) {
            logger.debug("Could not read version.properties", e);
        }
        return null;
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

    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    static boolean isNewerVersion(String latest, String current) {
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

    /**
     * Sets up a system tray icon (supported on Windows, macOS, and most Linux desktop
     * environments) so the app can keep monitoring credential expiration in the background
     * after the window is closed. Falls back to normal exit-on-close if the tray, or icon
     * loading, isn't available on this platform/environment.
     */
    private void initializeSystemTray() {
        if (!SystemTray.isSupported()) {
            logger.info("System tray is not supported on this platform; window close will exit the app.");
            return;
        }

        try {
            Image trayImage = Toolkit.getDefaultToolkit().getImage(getClass().getResource("/saml_swing_icon_small.png"));

            PopupMenu trayMenu = new PopupMenu();
            MenuItem showItem = new MenuItem("Show");
            showItem.addActionListener(e -> restoreFromTray());
            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> exitApplication());
            trayMenu.add(showItem);
            trayMenu.addSeparator();
            trayMenu.add(exitItem);

            trayIcon = new TrayIcon(trayImage, "AWS IDP SAML Client", trayMenu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> restoreFromTray());
            SystemTray.getSystemTray().add(trayIcon);

            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    setVisible(false);
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to initialize system tray icon; window close will exit the app.", e);
            trayIcon = null;
        }
    }

    private void restoreFromTray() {
        boolean wasVisible = isVisible();
        setVisible(true);
        setExtendedState(JFrame.NORMAL);
        toFront();
        requestFocus();
        if (!wasVisible) {
            // First real show when launched minimized skips main()'s post-show fix; apply it here.
            syncWindowPositionWithWindowManager(this);
        }
    }

    private void exitApplication() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        System.exit(0);
    }

    private void startStatusPolling() {
        statusRefreshTimer = new Timer(30000, e -> refreshStatusTable());
        statusRefreshTimer.setRepeats(true);
        statusRefreshTimer.start();
    }

    /**
     * Fires a tray notification the first time a profile's credentials cross into the
     * expiry warning window, so the user doesn't have to keep the window open and watch
     * the status table. Keyed by the exact expiration instant so a renewed credential
     * (new expiration) can trigger a fresh notification later.
     */
    private void maybeNotifyExpiringSoon(String profile, Instant expiration, Duration remaining) {
        if (trayIcon == null || !databaseManager.getTrayNotificationsEnabled()) {
            return;
        }
        if (remaining.compareTo(EXPIRY_WARNING_THRESHOLD) > 0) {
            return;
        }
        if (expiration.equals(lastNotifiedExpiration.get(profile))) {
            return;
        }

        lastNotifiedExpiration.put(profile, expiration);
        trayIcon.displayMessage(
            "AWS credentials expiring soon",
            "Profile \"" + profile + "\" expires in " + formatDuration(remaining) + ".",
            TrayIcon.MessageType.WARNING
        );
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
                    Duration remaining = Duration.between(now, expiration);
                    timeRemaining = formatDuration(remaining);
                    maybeNotifyExpiringSoon(profile, expiration, remaining);
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
            if (!credentialRequestInProgress) {
                statusLabel.setText("Status refreshed.");
            }
        } catch (Exception e) {
            if (!credentialRequestInProgress) {
                statusLabel.setText("Failed to update status table: " + e.getMessage());
            }
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
            String lastUsedProfile = databaseManager.getLastUsedProfile();

            // JComboBox auto-selects the first added item, firing the selection listener
            // before the real last-used profile can be restored below; suppress persistence
            // during this programmatic rebuild so that transient selection doesn't clobber it.
            loadingProfiles = true;
            try {
                profileComboBox.removeAllItems();
                for (String profile : profiles) {
                    profileComboBox.addItem(profile);
                }
                if (lastUsedProfile != null && profiles.contains(lastUsedProfile)) {
                    profileComboBox.setSelectedItem(lastUsedProfile);
                }
            } finally {
                loadingProfiles = false;
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Error loading profiles: " + e.getMessage(),
                "Configuration Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveLastUsedProfile() {
        if (loadingProfiles) {
            return;
        }
        String selectedProfile = (String) profileComboBox.getSelectedItem();
        if (selectedProfile != null) {
            databaseManager.setLastUsedProfile(selectedProfile);
        }
    }

    private class RequestCredentialsListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            requestCredentialsForProfile((String) profileComboBox.getSelectedItem());
        }
    }

    private void requestCredentialsForProfile(String selectedProfile) {
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
        credentialRequestInProgress = true;
        loginProgressBar.setVisible(true);
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
                credentialRequestInProgress = false;
                loginProgressBar.setVisible(false);

                try {
                    get(); // Check for exceptions
                    refreshStatusTable();
                    updateCredentialButtons();
                    statusLabel.setText("Credentials successfully obtained for profile: " + selectedProfile);
                    JOptionPane.showMessageDialog(SwingMain.this,
                        "Credentials successfully obtained for profile: " + selectedProfile,
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    statusLabel.setText("Error obtaining credentials: " + ex.getMessage());
                    JOptionPane.showMessageDialog(SwingMain.this,
                        "Error obtaining credentials: " + ex.getMessage(),
                        "Authentication Error",
                        JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private static class StatusTableCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (column == 1 && value instanceof String status) {
                switch (status) {
                    case "VALID" -> component.setForeground(new Color(0, 128, 0));
                    case "EXPIRED" -> component.setForeground(expiredForeground());
                    default -> component.setForeground(unknownForeground());
                }
            } else {
                component.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            }
            return component;
        }

        // Re-read per paint rather than caching statically so a theme switch takes effect immediately.
        private static Color unknownForeground() {
            Color disabled = UIManager.getColor("Label.disabledForeground");
            return disabled != null ? disabled : Color.GRAY;
        }

        // FlatLaf.isLafDark() returns false for non-FlatLaf LaFs (Nimbus/Metal), which falls
        // through to the light-background red — a sensible default there too.
        private static Color expiredForeground() {
            return FlatLaf.isLafDark() ? new Color(255, 110, 110) : Color.RED;
        }
    }

    private void showConfigurationDialog() {
        ConfigurationDialog dialog = new ConfigurationDialog(this, configManager, databaseManager, passwordManager);
        dialog.setVisible(true);
    }

    private void showProfileManagerDialog() {
        ProfileManagerDialog dialog = new ProfileManagerDialog(this, configManager);
        dialog.setVisible(true);
        if (dialog.isProfilesChanged()) {
            loadProfiles();
            refreshStatusTable();
        }
    }

    private JPopupMenu createTableContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem requestItem = new JMenuItem("Request Credentials");
        requestItem.addActionListener(e -> requestCredentialsForProfile((String) profileComboBox.getSelectedItem()));
        menu.add(requestItem);

        JMenuItem showItem = new JMenuItem("Show Credentials");
        showItem.addActionListener(e -> showCredentialsDialogForProfile((String) profileComboBox.getSelectedItem(), false, true));
        menu.add(showItem);

        JMenuItem showEncryptedItem = new JMenuItem("Show Encrypted Credentials");
        showEncryptedItem.addActionListener(e -> showCredentialsDialogForProfile((String) profileComboBox.getSelectedItem(), true, false));
        menu.add(showEncryptedItem);

        JMenuItem openConsoleItem = new JMenuItem("Open Console");
        openConsoleItem.addActionListener(e -> openAwsConsoleForProfile((String) profileComboBox.getSelectedItem()));
        menu.add(openConsoleItem);

        menu.addSeparator();

        JMenuItem deleteItem = new JMenuItem("Delete Profile...");
        deleteItem.addActionListener(e -> deleteProfileFromContextMenu((String) profileComboBox.getSelectedItem()));
        menu.add(deleteItem);

        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                String profile = (String) profileComboBox.getSelectedItem();
                boolean hasCredentials = profile != null && credentialManager.getCredentials(profile) != null;
                java.nio.file.Path publicKeyPath = java.nio.file.Paths.get(System.getProperty("user.home"), ".aws", "public_key.pem");
                boolean hasPublicKey = java.nio.file.Files.exists(publicKeyPath);

                requestItem.setEnabled(profile != null);
                showItem.setEnabled(hasCredentials);
                showEncryptedItem.setEnabled(hasCredentials && hasPublicKey);
                openConsoleItem.setEnabled(hasCredentials);
                deleteItem.setEnabled(profile != null);
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });

        return menu;
    }

    private void deleteProfileFromContextMenu(String profileName) {
        if (profileName == null) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete profile \"" + profileName + "\"? This cannot be undone.",
            "Delete Profile",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            configManager.deleteProfile(profileName);
            loadProfiles();
            refreshStatusTable();
        } catch (Exception ex) {
            logger.error("Failed to delete profile: {}", profileName, ex);
            JOptionPane.showMessageDialog(this,
                "Failed to delete profile: " + ex.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showCredentialsDialog(boolean showEncrypted, boolean showPlaintext) {
        showCredentialsDialogForProfile((String) profileComboBox.getSelectedItem(), showEncrypted, showPlaintext);
    }

    private void showCredentialsDialogForProfile(String selectedProfile, boolean showEncrypted, boolean showPlaintext) {
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
        openConsoleButton.setEnabled(hasCredentials);
    }

    private void openAwsConsole() {
        openAwsConsoleForProfile((String) profileComboBox.getSelectedItem());
    }

    private void openAwsConsoleForProfile(String selectedProfile) {
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

        openConsoleButton.setEnabled(false);
        openConsoleButton.setText("Opening...");
        statusLabel.setText("Opening AWS Console for profile: " + selectedProfile + "...");

        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                return AwsConsoleLauncher.buildLoginUrl(credentials);
            }

            @Override
            protected void done() {
                openConsoleButton.setEnabled(true);
                openConsoleButton.setText("Open Console");

                try {
                    String loginUrl = get();
                    Desktop.getDesktop().browse(new java.net.URI(loginUrl));
                    statusLabel.setText("Opened AWS Console for profile: " + selectedProfile);
                } catch (Exception ex) {
                    statusLabel.setText("Failed to open AWS Console: " + ex.getMessage());
                    logger.error("Failed to open AWS Console for profile: {}", selectedProfile, ex);
                    JOptionPane.showMessageDialog(SwingMain.this,
                        "Failed to open AWS Console: " + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
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

            SwingMain mainWindow = new SwingMain();
            boolean startMinimized = mainWindow.trayIcon != null && mainWindow.databaseManager.getStartMinimizedToTray();
            if (!startMinimized) {
                mainWindow.setVisible(true);
                syncWindowPositionWithWindowManager(mainWindow);
            }
        });
    }

    /**
     * On Linux/X11 the window manager confirms a window's real on-screen position
     * asynchronously after setVisible(true); FlatLaf's heavyweight popups (e.g. the
     * File menu) compute their screen position from that value, so clicking a menu
     * before the confirmation lands can render the popup at (0,0). Manually moving
     * the window fixes it by forcing a fresh position round-trip — nudge it
     * programmatically right after showing it so the fix applies before the user
     * can click anything.
     */
    private static void syncWindowPositionWithWindowManager(Window window) {
        Point location = window.getLocation();
        window.setLocation(location.x + 1, location.y);
        window.setLocation(location.x, location.y);
    }
}