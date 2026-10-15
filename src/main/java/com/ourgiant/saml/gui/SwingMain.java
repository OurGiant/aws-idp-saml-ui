package com.ourgiant.saml.gui;

import com.ourgiant.saml.ThemeManager;
import com.ourgiant.saml.core.AwsConfigFile;
import com.ourgiant.saml.core.AwsConsoleLauncher;
import com.ourgiant.saml.core.BatchRefreshRunner;
import com.ourgiant.saml.core.ConfigManager;
import com.ourgiant.saml.core.CredentialManager;
import com.ourgiant.saml.core.CredentialRequestError;
import com.ourgiant.saml.core.DatabaseManager;
import com.ourgiant.saml.core.PasswordManager;
import com.ourgiant.saml.core.ProfileBundle;
import com.ourgiant.saml.core.ProfileBundleImporter;
import com.ourgiant.saml.core.SamlAuthenticator;
import com.ourgiant.saml.core.TokenStateManager;
import com.ourgiant.saml.core.WebDriverFactory;
import com.ourgiant.saml.util.StartupLog;

import com.formdev.flatlaf.FlatLaf;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Main Swing application for AWS SAML authentication
 */
public class SwingMain extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(SwingMain.class);

    // Status table model columns. Named so that adding a column (as #185 did) can't silently
    // leave code reading the wrong one (#206).
    private static final int PROFILE_COLUMN = 0;
    private static final int STATUS_COLUMN = 2;
    // "Expiring soon" lead time (#228). Re-read from the database at the start of every status
    // refresh, so a change in Configuration applies on the next refresh without a restart.
    private Duration expiryWarningThreshold = Duration.ofMinutes(DatabaseManager.EXPIRY_WARNING_CHOICES.get(0));
    // How long to wait before retrying an auto-renew attempt for the same pinned profile after
    // one fails (e.g. it needs an interactive MFA step auto-renew can't complete headlessly) --
    // without this, a profile stuck in that state would be retried every statusRefreshTimer
    // tick (30s), hammering the SSO provider indefinitely.
    private static final Duration AUTO_RENEW_RETRY_COOLDOWN = Duration.ofMinutes(5);

    private JComboBox<String> profileComboBox;
    private JCheckBox showBrowserCheckBox;
    private JButton requestCredentialsButton;
    private JButton showEncryptedButton;
    private JButton showCredentialsButton;
    private JButton openConsoleButton;
    private JMenuItem batchRefreshMenuItem;
    private JMenuItem refreshSelectedMenuItem;
    private JButton batchCancelButton;

    private DefaultTableModel tokenStatusTableModel;
    private JTable tokenStatusTable;
    private TableRowSorter<DefaultTableModel> tokenStatusRowSorter;
    private JTextField profileFilterField;
    private JCheckBox validStatusFilterCheckBox;
    private JCheckBox expiredStatusFilterCheckBox;
    private JCheckBox unknownStatusFilterCheckBox;
    private JLabel lastRefreshedLabel;
    private JLabel statusLabel;
    private JProgressBar loginProgressBar;
    private Timer statusRefreshTimer;
    private volatile boolean credentialRequestInProgress = false;
    private SamlAuthenticator activeAuthenticator;
    private boolean credentialRequestCancelledByUser = false;
    private boolean loadingProfiles = false;
    private String contextMenuTargetProfile;

    private TrayIcon trayIcon;
    private PopupMenu trayMenu;
    // What trayMenu currently shows; it's only rebuilt when this changes (see updateTrayMenu()).
    private TrayMenuEntries lastTrayMenuEntries;
    private final Map<String, Instant> lastNotifiedExpiration = new HashMap<>();
    private final Map<String, Instant> lastAutoRenewAttempt = new HashMap<>();
    private final Set<String> expiringSoonProfiles = new HashSet<>();
    private final List<String> pinnedProfileOrder = new ArrayList<>();

    private ConfigManager configManager;
    private CredentialManager credentialManager;
    private TokenStateManager tokenStateManager;
    private DatabaseManager databaseManager;
    private PasswordManager passwordManager;
    private BatchRefreshRunner batchRefreshRunner;

    // Ephemeral, cookie-isolated browser windows opened by "Open Console" (see #169). Tracked
    // so a JVM shutdown hook can quit() them: without it, EXIT_ON_CLOSE (no tray icon) and other
    // exit paths bypass exitApplication() entirely and would leak orphaned chromedriver/
    // geckodriver processes, the same failure mode already fixed once for the login flow (#84).
    private final List<WebDriver> openConsoleDrivers = Collections.synchronizedList(new ArrayList<>());

    // Last bounds while the window was in its normal (not maximized/minimized) state. getBounds()
    // on a maximized window returns the maximized size, which isn't what should be restored when
    // the user later un-maximizes, so this is tracked separately as the window moves/resizes.
    private Rectangle lastNormalBounds;

    // Startup dialog coordination (see onStartupComplete()).
    private boolean startupDialogsDone;
    private boolean startupDialogsDeferredUntilShown;
    private String[] deferredUpdateRelease;
    // lastNormalBounds as of the moment the window was maximized, reapplied on un-maximize (see trackWindowBounds()).
    private Rectangle boundsBeforeMaximize;

    public SwingMain() {
        configManager = new ConfigManager();
        credentialManager = new CredentialManager();
        tokenStateManager = new TokenStateManager();
        databaseManager = new DatabaseManager();
        passwordManager = new PasswordManager(databaseManager);
        batchRefreshRunner = new BatchRefreshRunner(configManager, credentialManager, passwordManager, databaseManager);

        Runtime.getRuntime().addShutdownHook(new Thread(this::quitOpenConsoleDrivers, "console-driver-cleanup"));

        // Set theme
        setLookAndFeel();

        initializeUI();
        loadProfiles();
        refreshStatusTable();
        startStatusPolling();
        checkForUpdatesInBackground();
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

        tokenStatusTableModel = new DefaultTableModel(new String[]{"Profile", "Account Number", "Status", "Expires At", "Time Remaining"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        tokenStatusTable = new JTable(tokenStatusTableModel);
        tokenStatusTable.setFillsViewportHeight(true);
        tokenStatusTable.setRowHeight(26);
        tokenStatusTable.setToolTipText("Click a row to select that profile above, drag a row to pin/reorder favorites, "
            + "or right-click for actions. Click a column header to sort.");
        tokenStatusTable.setDefaultRenderer(Object.class, new StatusTableCellRenderer(expiringSoonProfiles, pinnedProfileOrder));
        tokenStatusRowSorter = new TableRowSorter<>(tokenStatusTableModel);
        tokenStatusTable.setRowSorter(tokenStatusRowSorter);
        // Single source of truth for "which profile is selected to act on": any change to the
        // table's selected row (mouse click, keyboard arrow navigation, or the context menu's
        // own setRowSelectionInterval below) syncs the combo box, instead of each interaction
        // path needing its own manual profileComboBox.setSelectedItem(...) call.
        tokenStatusTable.getSelectionModel().addListSelectionListener(e -> {
            updateRefreshSelectedMenuItemEnabled();
            if (e.getValueIsAdjusting()) {
                return;
            }
            int row = tokenStatusTable.getSelectedRow();
            if (row >= 0) {
                profileComboBox.setSelectedItem(tokenStatusTable.getValueAt(row, 0));
            }
        });
        tokenStatusTable.setDragEnabled(true);
        tokenStatusTable.setDropMode(DropMode.INSERT_ROWS);
        tokenStatusTable.setTransferHandler(new ProfileRowTransferHandler());
        JPopupMenu tableContextMenu = createTableContextMenu();
        tokenStatusTable.addMouseListener(new java.awt.event.MouseAdapter() {
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
                String profile = (String) tokenStatusTableModel.getValueAt(tokenStatusTable.convertRowIndexToModel(row), 0);
                // The dropdown can't reflect profiles that only exist via saved credentials/token
                // state (not defined in samlsts) — setSelectedItem silently no-ops for those. Track
                // the right-clicked profile directly so menu actions always target the right row.
                contextMenuTargetProfile = profile;
                tableContextMenu.show(tokenStatusTable, e.getX(), e.getY());
            }
        });

        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel filterLabel = new JLabel("Filter:");
        filterPanel.add(filterLabel);
        profileFilterField = new JTextField(20);
        profileFilterField.setToolTipText("Narrow the table below to profiles whose name contains this text");
        filterLabel.setLabelFor(profileFilterField);
        filterPanel.add(profileFilterField);
        profileFilterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { applyFilters(); }

            @Override
            public void removeUpdate(DocumentEvent e) { applyFilters(); }

            @Override
            public void changedUpdate(DocumentEvent e) { applyFilters(); }
        });

        filterPanel.add(new JLabel("Status:"));
        validStatusFilterCheckBox = new JCheckBox("Valid", true);
        expiredStatusFilterCheckBox = new JCheckBox("Expired", true);
        unknownStatusFilterCheckBox = new JCheckBox("Unknown", true);
        for (JCheckBox statusCheckBox : new JCheckBox[]{validStatusFilterCheckBox, expiredStatusFilterCheckBox, unknownStatusFilterCheckBox}) {
            statusCheckBox.setToolTipText("Uncheck to hide profiles with this status from the table below");
            statusCheckBox.addActionListener(e -> applyFilters());
            filterPanel.add(statusCheckBox);
        }
        tokenStatusPanel.add(filterPanel, BorderLayout.NORTH);

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

        // BorderLayout, not FlowLayout: a long status message (batch/grouped-login progress
        // text in particular, e.g. "Logging in once for N profiles sharing ...'s identity
        // provider: ...") must never be able to wrap the progress bar/cancel button onto a
        // second row FlowLayout would've had to invent — the fixed-size window (see
        // setMinimumSize below) never grows to show that second row, silently pushing the
        // cancel button out of visibility while a batch is actually running. Anchoring the
        // progress bar/cancel button to EAST keeps them always visible; the CENTER label just
        // clips instead, with the full text available via tooltip (see the propertyChange
        // listener below) rather than needing every statusLabel.setText(...) call site updated.
        JPanel statusPanel = new JPanel(new BorderLayout(8, 0));
        statusPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        statusLabel = new JLabel("Ready");
        statusLabel.addPropertyChangeListener("text", e -> statusLabel.setToolTipText((String) e.getNewValue()));
        statusPanel.add(statusLabel, BorderLayout.CENTER);

        JPanel statusEastPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        loginProgressBar = new JProgressBar();
        loginProgressBar.setPreferredSize(new Dimension(120, 16));
        loginProgressBar.setIndeterminate(true);
        loginProgressBar.setVisible(false);
        statusEastPanel.add(loginProgressBar);
        // Only shown while a batch refresh is actually running (see
        // refreshExpiringOrExpiredProfiles()), so cancelling stays a single visible click away
        // in the moment it matters without giving the batch action a permanent seat on the
        // main screen the rest of the time.
        batchCancelButton = new JButton("Cancel Batch Refresh");
        batchCancelButton.addActionListener(e -> cancelCredentialRequest());
        batchCancelButton.setVisible(false);
        statusEastPanel.add(batchCancelButton);
        statusPanel.add(statusEastPanel, BorderLayout.EAST);

        add(statusPanel, BorderLayout.SOUTH);

        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(null);
        restoreWindowBounds();
        trackWindowBounds();
    }

    /** Reapplies the size/position saved by {@link #saveWindowBounds()}, if it still fits on a connected screen. */
    private void restoreWindowBounds() {
        WindowBounds saved = WindowBounds.parse(databaseManager.getMainWindowBounds());
        if (saved == null) {
            return;
        }
        Rectangle fitted = saved.fitTo(WindowBounds.currentScreens(), getMinimumSize());
        if (fitted == null) {
            logger.info("Saved window position {} is off every connected screen; using the default position", saved.normalBounds());
        } else {
            setBounds(fitted);
        }
        if (saved.maximized()) {
            boundsBeforeMaximize = getBounds();
            setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
        }
    }

    private void trackWindowBounds() {
        lastNormalBounds = getBounds();
        // Window managers' own restore geometry isn't reliable (e.g. on X11 a window first shown
        // maximized un-maximizes a frame border smaller each time, compounding across launches),
        // so the remembered normal bounds are reapplied explicitly on un-maximize.
        addWindowStateListener(e -> {
            boolean wasMaximized = (e.getOldState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
            boolean isMaximized = (e.getNewState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
            if (!wasMaximized && isMaximized) {
                boundsBeforeMaximize = new Rectangle(lastNormalBounds);
            } else if (wasMaximized && !isMaximized && boundsBeforeMaximize != null) {
                Rectangle target = boundsBeforeMaximize;
                lastNormalBounds = new Rectangle(target);
                SwingUtilities.invokeLater(() -> setBounds(target));
            }
        });
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                rememberNormalBounds();
            }

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                rememberNormalBounds();
            }
        });
        // Saves on every close, whether it hides to the tray or exits (no tray: EXIT_ON_CLOSE).
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveWindowBounds();
            }
        });
    }

    private void rememberNormalBounds() {
        if (isShowing() && getExtendedState() == JFrame.NORMAL) {
            lastNormalBounds = getBounds();
        }
    }

    private void saveWindowBounds() {
        if (lastNormalBounds == null) {
            return;
        }
        boolean maximized = (getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
        try {
            databaseManager.setMainWindowBounds(new WindowBounds(lastNormalBounds, maximized).serialize());
        } catch (Exception e) {
            logger.warn("Failed to save main window bounds", e);
        }
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

        JMenuItem exportProfilesMenuItem = new JMenuItem("Export Profiles...");
        exportProfilesMenuItem.setMnemonic(KeyEvent.VK_E);
        exportProfilesMenuItem.setToolTipText("Save profiles, identity providers and app settings to a file (no credentials or passwords)");
        exportProfilesMenuItem.addActionListener(e -> exportProfiles());
        fileMenu.add(exportProfilesMenuItem);

        JMenuItem importProfilesMenuItem = new JMenuItem("Import Profiles...");
        importProfilesMenuItem.setMnemonic(KeyEvent.VK_I);
        importProfilesMenuItem.setToolTipText("Add profiles (and optionally app settings) from an exported file");
        importProfilesMenuItem.addActionListener(e -> importProfiles());
        fileMenu.add(importProfilesMenuItem);

        fileMenu.addSeparator();

        JMenuItem exitMenuItem = new JMenuItem("Exit");
        exitMenuItem.setMnemonic(KeyEvent.VK_X);
        exitMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        exitMenuItem.addActionListener(e -> exitApplication());
        fileMenu.add(exitMenuItem);

        menuBar.add(fileMenu);

        JMenu actionsMenu = new JMenu("Actions");
        actionsMenu.setMnemonic(KeyEvent.VK_A);

        batchRefreshMenuItem = new JMenuItem("Refresh Expiring/Expired Profiles...");
        batchRefreshMenuItem.setMnemonic(KeyEvent.VK_E);
        batchRefreshMenuItem.addActionListener(e -> refreshExpiringOrExpiredProfiles());
        updateExpiryWarningTexts();
        actionsMenu.add(batchRefreshMenuItem);

        refreshSelectedMenuItem = new JMenuItem("Refresh Selected Profiles...");
        refreshSelectedMenuItem.setMnemonic(KeyEvent.VK_S);
        refreshSelectedMenuItem.addActionListener(e -> refreshSelectedProfiles());
        refreshSelectedMenuItem.setToolTipText("Renew credentials (via browser login) for the profile(s) "
            + "currently selected in the status table, regardless of their current status");
        refreshSelectedMenuItem.setEnabled(false); // Nothing selected yet; kept in sync by the table's selection listener.
        actionsMenu.add(refreshSelectedMenuItem);

        menuBar.add(actionsMenu);

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem whatsNewMenuItem = new JMenuItem("What's New");
        whatsNewMenuItem.setMnemonic(KeyEvent.VK_W);
        whatsNewMenuItem.setToolTipText("Open the release history on GitHub: " + WhatsNew.RELEASES_URL);
        whatsNewMenuItem.addActionListener(e -> AboutDialog.openTrustedUrl(this, WhatsNew.RELEASES_URL));
        helpMenu.add(whatsNewMenuItem);

        String notesVersion = WhatsNew.latestNotesFor(WhatsNew.bundledVersions(), AboutDialog.resolveCurrentVersion());
        JMenuItem releaseNotesMenuItem = new JMenuItem("Release Notes for This Version...");
        releaseNotesMenuItem.setMnemonic(KeyEvent.VK_N);
        releaseNotesMenuItem.setEnabled(notesVersion != null);
        releaseNotesMenuItem.setToolTipText(notesVersion != null
            ? "What changed in version " + notesVersion : "No release notes are bundled with this build");
        releaseNotesMenuItem.addActionListener(e -> showReleaseNotes(notesVersion));
        helpMenu.add(releaseNotesMenuItem);

        JMenuItem usageTipsMenuItem = new JMenuItem("Usage Tips...");
        usageTipsMenuItem.setMnemonic(KeyEvent.VK_U);
        usageTipsMenuItem.setToolTipText("Tips for features that are easy to miss");
        usageTipsMenuItem.addActionListener(e -> showUsageTips());
        helpMenu.add(usageTipsMenuItem);

        helpMenu.addSeparator();

        JMenuItem viewLogsMenuItem = new JMenuItem("View Logs...");
        viewLogsMenuItem.setMnemonic(KeyEvent.VK_L);
        viewLogsMenuItem.addActionListener(e -> new LogViewerDialog(this).setVisible(true));
        helpMenu.add(viewLogsMenuItem);

        JMenuItem aboutMenuItem = new JMenuItem("About...");
        aboutMenuItem.setMnemonic(KeyEvent.VK_A);
        aboutMenuItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(aboutMenuItem);

        menuBar.add(helpMenu);

        return menuBar;
    }

    private static final String PROFILE_EXPORT_FILE_NAME = "aws-idp-saml-ui-profiles.ini";

    private JFileChooser profileExportChooser() {
        JFileChooser chooser = new JFileChooser(System.getProperty("user.home"));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Profile exports (*.ini)", "ini"));
        return chooser;
    }

    private void exportProfiles() {
        JFileChooser chooser = profileExportChooser();
        chooser.setDialogTitle("Export Profiles");
        chooser.setSelectedFile(new java.io.File(System.getProperty("user.home"), PROFILE_EXPORT_FILE_NAME));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.nio.file.Path file = chooser.getSelectedFile().toPath();
        if (!file.getFileName().toString().contains(".")) {
            file = file.resolveSibling(file.getFileName() + ".ini");
        }
        if (java.nio.file.Files.exists(file) && JOptionPane.showConfirmDialog(this,
                file.getFileName() + " already exists. Replace it?", "Export Profiles",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        exportProfilesTo(file);
    }

    /** Writes the export and reports it; separate from the file chooser so it can be driven directly. */
    void exportProfilesTo(java.nio.file.Path file) {
        try {
            Map<String, String> settings = new HashMap<>();
            for (String key : ProfileBundle.EXPORTED_SETTINGS) {
                settings.put(key, databaseManager.getConfig(key));
            }
            Set<String> profiles = new HashSet<>(configManager.getAvailableProfiles());
            List<String> pinned = databaseManager.getPinnedProfilesInOrder().stream().filter(profiles::contains).toList();
            ProfileBundle bundle = ProfileBundle.of(AboutDialog.resolveCurrentVersion(), configManager.getAllSections(),
                settings, pinned);
            bundle.write(file);
            logger.info("Exported {} profile(s) to {}", bundle.profiles().size(), file);
            statusLabel.setText("Exported " + bundle.profiles().size() + " profile(s) to " + file);
            JOptionPane.showMessageDialog(this,
                "Exported " + bundle.profiles().size() + " profile(s), identity providers and app settings to:\n" + file
                    + "\n\nThe file contains no credentials, passwords or encryption keys.",
                "Profiles Exported", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            logger.error("Failed to export profiles to {}", file, ex);
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(), "Export Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importProfiles() {
        JFileChooser chooser = profileExportChooser();
        chooser.setDialogTitle("Import Profiles");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        importProfilesFrom(chooser.getSelectedFile().toPath());
    }

    /** Previews and imports from an export file; separate from the file chooser so it can be driven directly. */
    void importProfilesFrom(java.nio.file.Path file) {
        ProfileBundle bundle;
        try {
            bundle = ProfileBundle.read(file);
        } catch (java.io.IOException ex) {
            logger.warn("Can't import profiles from {}", file, ex);
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Can't Import", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (bundle.profiles().isEmpty()) {
            JOptionPane.showMessageDialog(this, file.getFileName() + " doesn't contain any profiles.",
                "Nothing To Import", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        ProfileImportDialog dialog = new ProfileImportDialog(this, configManager, databaseManager, bundle, file);
        dialog.setVisible(true);
        ProfileBundleImporter.Result result = dialog.getResult();
        if (result == null) {
            return;
        }
        loadProfiles();
        refreshStatusTable();
        StringBuilder summary = new StringBuilder("Imported " + result.importedProfiles().size() + " profile(s): "
            + String.join(", ", result.importedProfiles()));
        if (!result.overwrittenProfiles().isEmpty()) {
            summary.append("\nOverwrote: ").append(String.join(", ", result.overwrittenProfiles()));
        }
        if (!result.providersAdded().isEmpty()) {
            summary.append("\nAdded identity providers: ").append(String.join(", ", result.providersAdded()));
        }
        if (result.settingsApplied()) {
            summary.append("\nApp settings and pinned profiles were imported.");
        }
        statusLabel.setText("Imported " + result.importedProfiles().size() + " profile(s) from " + file.getFileName());
        JOptionPane.showMessageDialog(this, summary.toString(), "Profiles Imported", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showAboutDialog() {
        new AboutDialog(this).setVisible(true);
    }

    private void showReleaseNotes(String version) {
        String html = version != null ? WhatsNew.notesHtml(List.of(version)) : null;
        if (html == null) {
            JOptionPane.showMessageDialog(this, "No release notes are bundled with this build.",
                "Release Notes", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new WhatsNewDialog(this, "Release Notes — AWS IdP SAML UI " + version, html).setVisible(true);
    }

    /**
     * Called once by main() when startup has finished. Startup dialogs (What's New after an
     * upgrade, #210, then a newer-release notice from the silent update check) wait for the
     * window's first appearance — immediately, or the first time it's opened from the tray — and
     * are shown one at a time rather than stacked or popping up while the window is hidden.
     */
    void onStartupComplete(boolean firstRun, boolean windowShown) {
        if (firstRun) {
            // A brand-new install has nothing to compare against; its first What's New is the next upgrade.
            databaseManager.setLastSeenWhatsNewVersion(AboutDialog.resolveCurrentVersion());
        }
        if (windowShown) {
            SwingUtilities.invokeLater(this::showStartupDialogs);
        } else {
            startupDialogsDeferredUntilShown = true;
        }
    }

    private void showStartupDialogs() {
        try {
            // At most one of these: release notes after an upgrade take the slot, otherwise tips (#211).
            if (!maybeShowWhatsNew() && databaseManager.getShowStartupTips()) {
                showUsageTips();
            }
        } catch (Exception e) {
            logger.warn("Could not show startup dialogs", e);
        } finally {
            startupDialogsDone = true;
            if (deferredUpdateRelease != null) {
                String[] release = deferredUpdateRelease;
                deferredUpdateRelease = null;
                showUpdateNotice(release);
            }
        }
    }

    /** @return true if the What's New dialog was shown */
    private boolean maybeShowWhatsNew() {
        String current = AboutDialog.resolveCurrentVersion();
        List<String> versions = WhatsNew.versionsToShow(WhatsNew.bundledVersions(),
            databaseManager.getLastSeenWhatsNewVersion(), current);
        if (versions.isEmpty()) {
            return false;
        }
        // Recorded before showing, so it appears once per version even if the app is closed while it's open.
        databaseManager.setLastSeenWhatsNewVersion(current);
        String html = WhatsNew.notesHtml(versions);
        if (html == null) {
            return false;
        }
        logger.info("Showing What's New for version(s) {}", versions);
        new WhatsNewDialog(this, "What's New in AWS IdP SAML UI " + versions.get(0), html).setVisible(true);
        return true;
    }

    /** Startup tips (#211) and Help → Usage Tips; persists "don't show again" and where to start next time. */
    private void showUsageTips() {
        List<UsageTips.Tip> tips = UsageTips.load();
        if (tips.isEmpty()) {
            return;
        }
        UsageTipsDialog dialog = new UsageTipsDialog(this, tips, databaseManager.getUsageTipsNextIndex(),
            databaseManager.getShowStartupTips());
        dialog.setVisible(true);
        databaseManager.setShowStartupTips(dialog.isShowOnStartup());
        databaseManager.setUsageTipsNextIndex(dialog.getNextIndex());
    }

    private void showUpdateNotice(String[] release) {
        databaseManager.setLastNotifiedUpdateVersion(release[0]);
        new AboutDialog(SwingMain.this, release).setVisible(true);
    }

    /**
     * Silently checks for a newer release in the background. Does nothing if up to date or if the
     * check fails (offline, rate-limited, etc.) — failures are only logged at debug level, matching
     * AboutDialog.fetchLatestRelease()'s own convention. If a newer version is found that hasn't
     * already been auto-surfaced, opens the About dialog pre-populated with that result (no
     * redundant re-fetch).
     */
    private void checkForUpdatesInBackground() {
        SwingWorker<String[], Void> worker = new SwingWorker<>() {
            @Override
            protected String[] doInBackground() {
                return AboutDialog.fetchLatestRelease();
            }

            @Override
            protected void done() {
                try {
                    String[] release = get();
                    if (release == null) {
                        return;
                    }
                    String latestTag = release[0];
                    String releaseUrl = release[1];
                    String latestVersion = latestTag.startsWith("v") ? latestTag.substring(1) : latestTag;
                    String currentVersion = AboutDialog.resolveCurrentVersion();
                    if (!AboutDialog.isNewerVersion(latestVersion, currentVersion)) {
                        return;
                    }
                    if (latestVersion.equals(databaseManager.getLastNotifiedUpdateVersion())) {
                        return;
                    }
                    String[] newerRelease = {latestVersion, releaseUrl};
                    if (startupDialogsDone) {
                        showUpdateNotice(newerRelease);
                    } else {
                        deferredUpdateRelease = newerRelease; // shown after What's New, see showStartupDialogs()
                    }
                } catch (Exception e) {
                    logger.debug("Silent update check failed", e);
                }
            }
        };
        worker.execute();
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

            trayMenu = new PopupMenu();
            updateTrayMenu();

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

            if (isMacOs()) {
                registerMacDockReopenHandler();
            }
        } catch (Exception e) {
            logger.warn("Failed to initialize system tray icon; window close will exit the app.", e);
            trayIcon = null;
        }
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * On macOS the tray icon alone isn't a reliable way back into a hidden window - it can go
     * unnoticed in a crowded menu bar. The Dock icon is always present (this app doesn't set
     * LSUIElement), so clicking it while the app is running with no visible windows is the more
     * discoverable fallback. There's no isSupported() capability query for AppReopenedListener; it
     * silently no-ops on platforms that don't fire the event, so this is only registered on macOS.
     */
    private void registerMacDockReopenHandler() {
        try {
            Desktop.getDesktop().addAppEventListener((java.awt.desktop.AppReopenedListener) e -> restoreFromTray());
        } catch (Exception e) {
            logger.warn("Failed to register macOS Dock reopen handler", e);
        }
    }

    /**
     * Rebuilds the tray menu (#195): Show, a submenu of quick actions per pinned profile, Refresh
     * Expiring/Expired, Exit. Called from every status refresh (which pin/unpin/reorder also
     * trigger) and whenever a request starts or finishes; a no-op unless what the menu would show
     * has actually changed.
     */
    private void updateTrayMenu() {
        if (trayMenu == null) {
            return;
        }
        // The tray is set up partway through initializeUI(), before the status table exists; until the
        // first refreshStatusTable() fills it, the menu just has no profile entries.
        Map<String, String> statuses = new HashMap<>();
        int rowCount = tokenStatusTableModel != null ? tokenStatusTableModel.getRowCount() : 0;
        for (int row = 0; row < rowCount; row++) {
            statuses.put((String) tokenStatusTableModel.getValueAt(row, PROFILE_COLUMN),
                (String) tokenStatusTableModel.getValueAt(row, STATUS_COLUMN));
        }
        TrayMenuEntries entries = TrayMenuEntries.build(pinnedProfileOrder, statuses, expiringSoonProfiles,
            credentialManager.getAllProfileNames(), statuses.keySet(), credentialRequestInProgress);
        if (entries.equals(lastTrayMenuEntries)) {
            return;
        }
        lastTrayMenuEntries = entries;

        trayMenu.removeAll();
        MenuItem showItem = new MenuItem("Show");
        showItem.addActionListener(e -> restoreFromTray());
        trayMenu.add(showItem);
        trayMenu.addSeparator();

        if (entries.profiles().isEmpty()) {
            MenuItem hint = new MenuItem("No pinned profiles (pin one from the status table)");
            hint.setEnabled(false);
            trayMenu.add(hint);
        }
        for (TrayMenuEntries.Entry entry : entries.profiles()) {
            Menu profileMenu = new Menu(entry.label());
            MenuItem requestItem = new MenuItem("Request Credentials");
            requestItem.setEnabled(entry.canRequest());
            requestItem.addActionListener(e -> requestCredentialsForProfile(entry.profile()));
            profileMenu.add(requestItem);
            MenuItem consoleItem = new MenuItem("Open Console");
            consoleItem.setEnabled(entry.canOpenConsole());
            consoleItem.addActionListener(e -> openAwsConsoleForProfile(entry.profile()));
            profileMenu.add(consoleItem);
            trayMenu.add(profileMenu);
        }

        trayMenu.addSeparator();
        MenuItem refreshItem = new MenuItem("Refresh Expiring/Expired Profiles...");
        refreshItem.setEnabled(!entries.requestInProgress());
        refreshItem.addActionListener(e -> refreshExpiringOrExpiredProfiles());
        trayMenu.add(refreshItem);
        trayMenu.addSeparator();
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> exitApplication());
        trayMenu.add(exitItem);
    }

    /**
     * When the window is hidden in the tray (e.g. the action was started from the tray menu), a
     * result is shown as a tray notification rather than a dialog popping up out of nowhere.
     * Returns false — so the caller shows its usual dialog — when the window is visible or tray
     * notifications are unavailable or turned off.
     */
    private boolean notifyIfHiddenInTray(String title, String message, TrayIcon.MessageType type) {
        if (isVisible() || trayIcon == null || !databaseManager.getTrayNotificationsEnabled()) {
            return false;
        }
        logger.info("Window hidden in tray; showing notification instead of a dialog: {} - {}", title, message);
        trayIcon.displayMessage(title, message, type);
        return true;
    }

    private void restoreFromTray() {
        boolean wasVisible = isVisible();
        setVisible(true);
        // Clear only the minimized bit, so a window restored maximized stays maximized.
        setExtendedState(getExtendedState() & ~JFrame.ICONIFIED);
        toFront();
        requestFocus();
        if (!wasVisible) {
            // First real show when launched minimized skips main()'s post-show fix; apply it here.
            syncWindowPositionWithWindowManager(this);
        }
        if (startupDialogsDeferredUntilShown) {
            startupDialogsDeferredUntilShown = false;
            SwingUtilities.invokeLater(this::showStartupDialogs);
        }
    }

    private void exitApplication() {
        if (isShowing()) {
            saveWindowBounds();
        }
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        System.exit(0);
    }

    private void quitOpenConsoleDrivers() {
        synchronized (openConsoleDrivers) {
            for (WebDriver driver : openConsoleDrivers) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    logger.warn("Failed to close a console browser window on shutdown", e);
                }
            }
            openConsoleDrivers.clear();
        }
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
        if (remaining.compareTo(expiryWarningThreshold) > 0) {
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

    private void updateExpiryWarningTexts() {
        if (batchRefreshMenuItem != null) {
            batchRefreshMenuItem.setToolTipText("Renew credentials (via browser login) for every profile that's "
                + "expired or within " + formatDuration(expiryWarningThreshold) + " of expiring");
        }
    }

    private void refreshStatusTable() {
        try {
            Duration threshold = Duration.ofMinutes(databaseManager.getExpiryWarningMinutes());
            if (!threshold.equals(expiryWarningThreshold)) {
                expiryWarningThreshold = threshold;
                updateExpiryWarningTexts();
            }
            tokenStatusTableModel.setRowCount(0);
            expiringSoonProfiles.clear();
            pinnedProfileOrder.clear();
            pinnedProfileOrder.addAll(databaseManager.getPinnedProfilesInOrder());
            List<String> availableProfiles = configManager.getAvailableProfiles();
            databaseManager.reconcileProfiles(new HashSet<>(availableProfiles));

            Set<String> profileSet = new TreeSet<>();
            profileSet.addAll(availableProfiles);
            profileSet.addAll(tokenStateManager.getAllExpirations().keySet());
            profileSet.addAll(credentialManager.getAllProfileNames());

            List<TokenStatusRow> rows = new ArrayList<>();
            Instant now = Instant.now();

            for (String profile : profileSet) {
                String accountNumber = configManager.getAccountNumber(profile);
                if (accountNumber == null || accountNumber.isBlank()) {
                    accountNumber = "N/A";
                }
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
                    if (remaining.compareTo(expiryWarningThreshold) <= 0) {
                        expiringSoonProfiles.add(profile);
                    }
                    maybeNotifyExpiringSoon(profile, expiration, remaining);
                } else {
                    status = "EXPIRED";
                    expiresAtText = formatInstant(expiration);
                    timeRemaining = "Expired";
                }

                rows.add(new TokenStatusRow(profile, accountNumber, status, expiresAtText, timeRemaining));
            }

            // Pinned profiles sort to the top in their persisted order; everything else falls
            // back to the existing status-then-name ordering.
            rows.sort((a, b) -> {
                int rankA = pinnedProfileOrder.indexOf(a.getProfile());
                int rankB = pinnedProfileOrder.indexOf(b.getProfile());
                if (rankA >= 0 || rankB >= 0) {
                    if (rankA < 0) return 1;
                    if (rankB < 0) return -1;
                    return rankA - rankB;
                }
                int statusOrder = getStatusOrder(a.getStatus()) - getStatusOrder(b.getStatus());
                if (statusOrder != 0) return statusOrder;
                return a.getProfile().compareTo(b.getProfile());
            });

            for (TokenStatusRow row : rows) {
                tokenStatusTableModel.addRow(new Object[]{row.getProfile(), row.getAccountNumber(), row.getStatus(), row.getExpiresAt(), row.getTimeRemaining()});
            }

            lastRefreshedLabel.setText("Last refreshed: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            if (!credentialRequestInProgress) {
                statusLabel.setText("Status refreshed.");
            }

            maybeAutoRenewPinnedProfiles();
            updateTrayMenu();
        } catch (Exception e) {
            if (!credentialRequestInProgress) {
                statusLabel.setText("Failed to update status table: " + e.getMessage());
            }
            System.err.println("Status table update failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void applyFilters() {
        List<RowFilter<Object, Object>> filters = new ArrayList<>();

        String filterText = profileFilterField.getText();
        if (filterText != null && !filterText.isBlank()) {
            filters.add(RowFilter.regexFilter("(?i)" + Pattern.quote(filterText), 0));
        }

        Set<String> allowedStatuses = new HashSet<>();
        if (validStatusFilterCheckBox.isSelected()) allowedStatuses.add("VALID");
        if (expiredStatusFilterCheckBox.isSelected()) allowedStatuses.add("EXPIRED");
        if (unknownStatusFilterCheckBox.isSelected()) allowedStatuses.add("UNKNOWN");
        filters.add(new RowFilter<>() {
            @Override
            public boolean include(Entry<?, ?> entry) {
                return allowedStatuses.contains(entry.getStringValue(STATUS_COLUMN));
            }
        });
        RowFilter<Object, Object> statusAndTextFilter = RowFilter.andFilter(filters);

        // Pinning is meant to keep favorites within easy reach regardless of how the rest of
        // the list is being filtered (#147), so a pinned profile bypasses both the text and
        // status filters above rather than being subject to them like any other row.
        RowFilter<Object, Object> pinnedOrFiltered = new RowFilter<>() {
            @Override
            public boolean include(Entry<?, ?> entry) {
                return pinnedProfileOrder.contains(entry.getStringValue(0)) || statusAndTextFilter.include(entry);
            }
        };
        tokenStatusRowSorter.setRowFilter(pinnedOrFiltered);
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

            // Pinned profiles surface first, same ordering as the status table.
            List<String> orderedProfiles = new ArrayList<>();
            for (String pinned : databaseManager.getPinnedProfilesInOrder()) {
                if (profiles.contains(pinned)) {
                    orderedProfiles.add(pinned);
                }
            }
            for (String profile : profiles) {
                if (!orderedProfiles.contains(profile)) {
                    orderedProfiles.add(profile);
                }
            }
            profiles = orderedProfiles;

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
            if (credentialRequestInProgress) {
                cancelCredentialRequest();
            } else {
                requestCredentialsForProfile((String) profileComboBox.getSelectedItem());
            }
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

        if (credentialRequestInProgress) {
            JOptionPane.showMessageDialog(SwingMain.this,
                "A credential request is already in progress. Cancel it first or wait for it to finish.",
                "Request In Progress",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Swap the button to a Cancel affordance during processing
        requestCredentialsButton.setText("Cancel");
        requestCredentialsButton.setToolTipText("Cancel the in-progress credential request");
        batchRefreshMenuItem.setEnabled(false);
        refreshSelectedMenuItem.setEnabled(false);
        credentialRequestInProgress = true;
        credentialRequestCancelledByUser = false;
        loginProgressBar.setVisible(true);
        updateTrayMenu();
        statusLabel.setText("Starting credential request for profile: " + selectedProfile + "...");

        SamlAuthenticator authenticator = new SamlAuthenticator(configManager, credentialManager, passwordManager,
            databaseManager.getWriteRegionToAwsConfigEnabled() ? AwsConfigFile.forCurrentUser() : null);
        activeAuthenticator = authenticator;

        // Run credential request in background thread
        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
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
                requestCredentialsButton.setText("Request Credentials");
                requestCredentialsButton.setToolTipText("Launch browser login and fetch AWS credentials for the selected profile");
                batchRefreshMenuItem.setEnabled(true);
                updateRefreshSelectedMenuItemEnabled();
                credentialRequestInProgress = false;
                loginProgressBar.setVisible(false);
                activeAuthenticator = null;
                updateTrayMenu();

                if (credentialRequestCancelledByUser) {
                    statusLabel.setText("Credential request cancelled for profile: " + selectedProfile);
                    return;
                }

                try {
                    get(); // Check for exceptions
                    refreshStatusTable();
                    updateCredentialButtons();
                    statusLabel.setText("Credentials successfully obtained for profile: " + selectedProfile);
                    if (!notifyIfHiddenInTray("Credentials obtained",
                            "Credentials successfully obtained for profile: " + selectedProfile, TrayIcon.MessageType.INFO)) {
                        JOptionPane.showMessageDialog(SwingMain.this,
                            "Credentials successfully obtained for profile: " + selectedProfile,
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception ex) {
                    CredentialRequestError error = CredentialRequestError.classify(ex);
                    statusLabel.setText(error.statusMessage());
                    if (!notifyIfHiddenInTray(error.title() + " (" + selectedProfile + ")",
                            error.guidance() + " Open the app for details.", TrayIcon.MessageType.ERROR)) {
                        showCredentialErrorDialog(selectedProfile, error);
                    }
                }
            }
        };
        worker.execute();
    }

    private void cancelCredentialRequest() {
        statusLabel.setText("Cancelling credential request...");
        credentialRequestCancelledByUser = true;
        if (activeAuthenticator != null) {
            activeAuthenticator.cancel();
        }
    }

    private void updateRefreshSelectedMenuItemEnabled() {
        refreshSelectedMenuItem.setEnabled(!credentialRequestInProgress && tokenStatusTable.getSelectedRowCount() > 0);
    }

    /**
     * Refreshes every profile currently EXPIRED or within expiryWarningThreshold of expiring,
     * so a user managing many profiles doesn't have to repeat "select profile, click Request
     * Credentials, wait" once per stale profile.
     */
    private void refreshExpiringOrExpiredProfiles() {
        List<String> targets = new ArrayList<>();
        for (int row = 0; row < tokenStatusTableModel.getRowCount(); row++) {
            String profile = (String) tokenStatusTableModel.getValueAt(row, PROFILE_COLUMN);
            String status = (String) tokenStatusTableModel.getValueAt(row, STATUS_COLUMN);
            if ("EXPIRED".equals(status) || expiringSoonProfiles.contains(profile)) {
                targets.add(profile);
            }
        }
        runBatchRefresh(targets, "Refresh Expiring/Expired Profiles",
            "No profiles are currently expired or expiring soon.");
    }

    /**
     * Silently kicks off a headless refresh for pinned profiles that are expired or expiring
     * soon, when the user has opted into "auto-renew pinned profiles" (#173). Runs from the
     * same status-polling tick that already computes expiringSoonProfiles, rather than a
     * separate timer. Unlike the menu-driven batch-refresh actions, this never prompts for
     * confirmation and never pops a completion dialog — it's a background timer tick, and an
     * unattended modal dialog would be disruptive — so a tray notification announces the
     * kickoff instead, and failures are reported the same way rather than silently swallowed.
     */
    private void maybeAutoRenewPinnedProfiles() {
        if (!databaseManager.getAutoRenewPinnedProfilesEnabled() || credentialRequestInProgress) {
            return;
        }

        Set<String> pinnedDueProfiles = new HashSet<>();
        for (int row = 0; row < tokenStatusTableModel.getRowCount(); row++) {
            String profile = (String) tokenStatusTableModel.getValueAt(row, PROFILE_COLUMN);
            String status = (String) tokenStatusTableModel.getValueAt(row, STATUS_COLUMN);
            if (pinnedProfileOrder.contains(profile) && ("EXPIRED".equals(status) || expiringSoonProfiles.contains(profile))) {
                pinnedDueProfiles.add(profile);
            }
        }

        // A pinned profile that's no longer due (renewed, unpinned, or removed) starts with a
        // fresh retry budget the next time it becomes due, rather than inheriting a stale cooldown.
        lastAutoRenewAttempt.keySet().retainAll(pinnedDueProfiles);

        Instant now = Instant.now();
        List<String> targets = new ArrayList<>();
        for (String profile : pinnedDueProfiles) {
            Instant lastAttempt = lastAutoRenewAttempt.get(profile);
            if (lastAttempt == null || Duration.between(lastAttempt, now).compareTo(AUTO_RENEW_RETRY_COOLDOWN) >= 0) {
                targets.add(profile);
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        for (String profile : targets) {
            lastAutoRenewAttempt.put(profile, now);
        }

        if (trayIcon != null && databaseManager.getTrayNotificationsEnabled()) {
            trayIcon.displayMessage(
                "Auto-renewing pinned profile(s)",
                "Starting credential renewal for: " + String.join(", ", targets),
                TrayIcon.MessageType.INFO
            );
        }

        startBatchRefresh(targets, false);
    }

    private void notifyAutoRenewFailures(List<BatchRefreshRunner.Result> failures) {
        if (trayIcon == null || !databaseManager.getTrayNotificationsEnabled()) {
            return;
        }
        String profiles = failures.stream().map(BatchRefreshRunner.Result::profile).collect(Collectors.joining(", "));
        trayIcon.displayMessage(
            "Auto-renew failed",
            "Could not renew: " + profiles + ". Refresh manually when convenient.",
            TrayIcon.MessageType.ERROR
        );
    }

    /**
     * Refreshes exactly the profile(s) currently selected in the status table, regardless of
     * their current status (#127) — e.g. proactively refreshing a still-valid profile before a
     * work session is a legitimate reason to select it, not just expired/expiring ones.
     */
    private void refreshSelectedProfiles() {
        List<String> targets = new ArrayList<>();
        for (int row : tokenStatusTable.getSelectedRows()) {
            targets.add((String) tokenStatusTable.getValueAt(row, 0));
        }
        runBatchRefresh(targets, "Refresh Selected Profiles",
            "No profiles are selected in the status table.");
    }

    /**
     * Wraps a potentially long message (e.g. several failed profiles' error detail
     * concatenated together) in a fixed-size scrollable text area, so a verbose message can
     * never make the dialog itself taller than the screen - the content scrolls instead.
     */
    private static JScrollPane scrollableMessage(String text) {
        JTextArea textArea = new JTextArea(text);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setCaretPosition(0);
        textArea.setBackground(UIManager.getColor("OptionPane.background"));
        textArea.setFont(UIManager.getFont("OptionPane.messageFont"));
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(500, 300));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        return scrollPane;
    }

    /**
     * Shared by refreshExpiringOrExpiredProfiles() and refreshSelectedProfiles() (#127):
     * confirms with the user, then sequentially drives a fresh credential request for every
     * given profile, sharing one SAML login per (samlProvider, username) group (#124). Shares
     * credentialRequestInProgress/activeAuthenticator/credentialRequestCancelledByUser with the
     * single-profile flow (requestCredentialsForProfile/cancelCredentialRequest) so no two of
     * these three actions can run concurrently, and Cancel works the same way for all of them.
     */
    private void runBatchRefresh(List<String> targets, String actionTitle, String emptyMessage) {
        if (credentialRequestInProgress) {
            JOptionPane.showMessageDialog(SwingMain.this,
                "A credential request is already in progress. Cancel it first or wait for it to finish.",
                "Request In Progress",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (targets.isEmpty()) {
            JOptionPane.showMessageDialog(SwingMain.this,
                emptyMessage,
                "Nothing To Refresh",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(SwingMain.this,
            "This will refresh " + targets.size() + " profile(s) via browser login, one at a time. Continue?",
            actionTitle,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        startBatchRefresh(targets, true);
    }

    /**
     * Shared by the interactive batch-refresh actions (which have already confirmed with the
     * user and want a completion summary dialog) and the silent auto-renew-pinned-profiles
     * check (which never prompts and only ever surfaces a tray notification, see
     * maybeAutoRenewPinnedProfiles()) — drives the actual SwingWorker/BatchRefreshRunner
     * execution and the busy UI state shared with the single-profile request flow.
     */
    private void startBatchRefresh(List<String> targets, boolean interactive) {
        batchRefreshMenuItem.setEnabled(false);
        refreshSelectedMenuItem.setEnabled(false);
        batchCancelButton.setVisible(true);
        requestCredentialsButton.setEnabled(false);
        credentialRequestInProgress = true;
        credentialRequestCancelledByUser = false;
        loginProgressBar.setVisible(true);
        updateTrayMenu();
        statusLabel.setText("Starting batch refresh for " + targets.size() + " profile(s)...");

        SwingWorker<List<BatchRefreshRunner.Result>, String> worker = new SwingWorker<>() {
            @Override
            protected List<BatchRefreshRunner.Result> doInBackground() {
                // Always headless (#149): watching N browser windows open one after another
                // for a multi-profile batch isn't a usable experience the "Show browser"
                // checkbox was ever designed for - that's a single-profile debugging aid. This
                // also means batch refresh always gets the shared-login grouping optimization
                // (see BatchRefreshRunner.run()), which showBrowser=true used to disable too.
                return batchRefreshRunner.run(
                    targets,
                    false,
                    () -> credentialRequestCancelledByUser,
                    authenticator -> activeAuthenticator = authenticator,
                    this::publish
                );
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    statusLabel.setText(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                batchRefreshMenuItem.setEnabled(true);
                batchCancelButton.setVisible(false);
                requestCredentialsButton.setEnabled(true);
                credentialRequestInProgress = false;
                updateRefreshSelectedMenuItemEnabled();
                loginProgressBar.setVisible(false);
                activeAuthenticator = null;
                updateTrayMenu();

                List<BatchRefreshRunner.Result> results;
                try {
                    results = get();
                } catch (Exception ex) {
                    statusLabel.setText("Batch refresh failed: " + ex.getMessage());
                    return;
                }

                refreshStatusTable();
                updateCredentialButtons();

                long succeeded = results.stream().filter(BatchRefreshRunner.Result::success).count();
                List<BatchRefreshRunner.Result> failures = results.stream().filter(r -> !r.success()).toList();
                boolean cancelled = credentialRequestCancelledByUser;
                int notAttempted = targets.size() - results.size();

                if (cancelled) {
                    statusLabel.setText(String.format(
                        "Batch refresh cancelled: %d succeeded, %d failed, %d not attempted.",
                        succeeded, failures.size(), notAttempted));
                } else {
                    statusLabel.setText(String.format(
                        "Batch refresh complete: %d succeeded, %d failed.", succeeded, failures.size()));
                }

                if (interactive) {
                    showBatchRefreshCompletionDialog(succeeded, failures, cancelled, notAttempted);
                } else if (!failures.isEmpty()) {
                    notifyAutoRenewFailures(failures);
                }
            }
        };
        worker.execute();
    }

    private void showBatchRefreshCompletionDialog(long succeeded, List<BatchRefreshRunner.Result> failures,
                                                    boolean cancelled, int notAttempted) {
        String summary = succeeded + " succeeded, " + failures.size() + " failed"
            + (cancelled ? ", " + notAttempted + " not attempted (cancelled)" : "")
            + (failures.isEmpty() ? "" : ": " + failures.stream().map(BatchRefreshRunner.Result::profile).collect(Collectors.joining(", ")));
        if (notifyIfHiddenInTray(cancelled ? "Batch refresh cancelled" : "Batch refresh complete", summary,
                failures.isEmpty() ? TrayIcon.MessageType.INFO : TrayIcon.MessageType.WARNING)) {
            return;
        }
        if (failures.isEmpty() && !cancelled) {
            JOptionPane.showMessageDialog(SwingMain.this,
                "Refreshed " + succeeded + " profile(s) successfully.",
                "Batch Refresh Complete",
                JOptionPane.INFORMATION_MESSAGE);
        } else {
            StringBuilder detail = new StringBuilder();
            detail.append(succeeded).append(" succeeded, ").append(failures.size()).append(" failed");
            if (cancelled) {
                detail.append(", ").append(notAttempted).append(" not attempted (cancelled)");
            }
            detail.append(".\n");
            for (BatchRefreshRunner.Result r : failures) {
                detail.append("\n- ").append(r.profile()).append(": ").append(r.detail());
            }
            JOptionPane.showMessageDialog(SwingMain.this,
                scrollableMessage(detail.toString()),
                cancelled ? "Batch Refresh Cancelled" : "Batch Refresh Complete With Errors",
                JOptionPane.WARNING_MESSAGE);
        }
    }

    private void showCredentialErrorDialog(String selectedProfile, CredentialRequestError error) {
        if (error.retryable()) {
            Object[] options = {"Retry", "Close"};
            int choice = JOptionPane.showOptionDialog(
                this,
                error.htmlMessage(),
                error.title(),
                JOptionPane.DEFAULT_OPTION,
                error.iconMessageType(),
                null,
                options,
                options[0]
            );
            if (choice == 0) {
                requestCredentialsForProfile(selectedProfile);
            }
        } else {
            JOptionPane.showMessageDialog(this,
                error.htmlMessage(),
                error.title(),
                error.iconMessageType());
        }
    }

    static class StatusTableCellRenderer extends DefaultTableCellRenderer {
        private final Set<String> expiringSoonProfiles;
        private final List<String> pinnedProfiles;

        StatusTableCellRenderer(Set<String> expiringSoonProfiles, List<String> pinnedProfiles) {
            this.expiringSoonProfiles = expiringSoonProfiles;
            this.pinnedProfiles = pinnedProfiles;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            // Registered as the table's default renderer (all columns), so bold-face the whole
            // pinned row rather than just the status column.
            String rowProfile = (String) table.getValueAt(row, 0);
            component.setFont(component.getFont().deriveFont(
                pinnedProfiles.contains(rowProfile) ? Font.BOLD : Font.PLAIN));

            int modelColumn = table.convertColumnIndexToModel(column);
            if ("Status".equals(table.getModel().getColumnName(modelColumn)) && value instanceof String status) {
                switch (status) {
                    case "VALID" -> {
                        // getValueAt (unlike the table model) takes view coordinates, so this
                        // stays correct under the row sorter/filter.
                        String profile = (String) table.getValueAt(row, 0);
                        component.setForeground(expiringSoonProfiles.contains(profile)
                            ? expiringSoonForeground() : new Color(0, 128, 0));
                    }
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

        // Same light/dark contrast reasoning as expiredForeground().
        private static Color expiringSoonForeground() {
            return FlatLaf.isLafDark() ? new Color(255, 193, 7) : new Color(184, 92, 0);
        }
    }

    private void showConfigurationDialog() {
        ConfigurationDialog dialog = new ConfigurationDialog(this, configManager, databaseManager, passwordManager);
        dialog.setVisible(true);
        refreshStatusTable(); // apply settings that affect the table now, e.g. the expiry warning lead time
    }

    private void showProfileManagerDialog() {
        ProfileManagerDialog dialog = new ProfileManagerDialog(this, configManager, () -> new ImportRolesDialog(this, configManager,
            () -> new SamlAuthenticator(configManager, credentialManager, passwordManager),
            databaseManager.getFastPassEnabled(), showBrowserCheckBox.isSelected()));
        dialog.setVisible(true);
        if (dialog.isProfilesChanged()) {
            loadProfiles();
            refreshStatusTable();
        }
    }

    /**
     * Drag-and-drop row reordering for the status table. Dragging a row onto another row
     * pins the dragged profile (if not already pinned) at that position, or moves it there
     * if it's already pinned — reordering always targets the pinned block specifically, since
     * unpinned rows fall back to the status/name ordering refreshStatusTable() already applies.
     */
    private class ProfileRowTransferHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            int viewRow = tokenStatusTable.getSelectedRow();
            if (viewRow < 0) {
                return null;
            }
            return new StringSelection((String) tokenStatusTable.getValueAt(viewRow, 0));
        }

        @Override
        public boolean canImport(TransferSupport support) {
            // Reordering is relative to what's visually above/below the drop point, which is
            // only well-defined against the pinned-first/status/name ordering this table
            // normally uses — decline while a column-header sort overrides that ordering.
            return support.isDrop()
                && support.isDataFlavorSupported(DataFlavor.stringFlavor)
                && tokenStatusRowSorter.getSortKeys().isEmpty();
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                String draggedProfile = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                int targetViewRow = ((JTable.DropLocation) support.getDropLocation()).getRow();
                reorderPinnedProfile(draggedProfile, targetViewRow);
                return true;
            } catch (Exception ex) {
                logger.warn("Failed to reorder pinned profile via drag-and-drop", ex);
                return false;
            }
        }
    }

    private void reorderPinnedProfile(String profile, int targetViewRow) {
        List<String> pinned = new ArrayList<>(databaseManager.getPinnedProfilesInOrder());
        pinned.remove(profile);

        int insertIndex;
        if (targetViewRow < 0 || targetViewRow >= tokenStatusTable.getRowCount()) {
            insertIndex = pinned.size();
        } else {
            String targetProfile = (String) tokenStatusTable.getValueAt(targetViewRow, 0);
            int existingIndex = pinned.indexOf(targetProfile);
            insertIndex = (existingIndex >= 0) ? existingIndex : pinned.size();
        }

        pinned.add(insertIndex, profile);
        databaseManager.setPinnedProfilesInOrder(pinned);
        statusLabel.setText("Pinned \"" + profile + "\" at position " + (insertIndex + 1) + ".");
        refreshStatusTable();
        loadProfiles();
    }

    private void toggleProfilePinned(String profileName) {
        if (profileName == null) {
            return;
        }
        boolean pinned = databaseManager.isProfilePinned(profileName);
        databaseManager.setProfilePinned(profileName, !pinned);
        statusLabel.setText((!pinned ? "Pinned \"" : "Unpinned \"") + profileName + "\".");
        refreshStatusTable();
        loadProfiles();
    }

    private JPopupMenu createTableContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem requestItem = new JMenuItem("Request Credentials");
        requestItem.addActionListener(e -> requestCredentialsForProfile(contextMenuTargetProfile));
        menu.add(requestItem);

        JMenuItem showItem = new JMenuItem("Show Credentials");
        showItem.addActionListener(e -> showCredentialsDialogForProfile(contextMenuTargetProfile, false, true));
        menu.add(showItem);

        JMenuItem showEncryptedItem = new JMenuItem("Show Encrypted Credentials");
        showEncryptedItem.addActionListener(e -> showCredentialsDialogForProfile(contextMenuTargetProfile, true, false));
        menu.add(showEncryptedItem);

        JMenuItem openConsoleItem = new JMenuItem("Open Console");
        openConsoleItem.addActionListener(e -> openAwsConsoleForProfile(contextMenuTargetProfile));
        menu.add(openConsoleItem);

        JMenuItem copyAccountItem = new JMenuItem("Copy Account Number");
        copyAccountItem.addActionListener(e -> copyAccountNumberForProfile(contextMenuTargetProfile));
        menu.add(copyAccountItem);

        menu.addSeparator();

        JMenuItem pinItem = new JMenuItem("Pin Profile");
        pinItem.addActionListener(e -> toggleProfilePinned(contextMenuTargetProfile));
        menu.add(pinItem);

        menu.addSeparator();

        JMenuItem deleteItem = new JMenuItem("Delete Profile...");
        deleteItem.addActionListener(e -> deleteProfileFromContextMenu(contextMenuTargetProfile));
        menu.add(deleteItem);

        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                String profile = contextMenuTargetProfile;
                boolean hasCredentials = profile != null && credentialManager.getCredentials(profile) != null;
                java.nio.file.Path publicKeyPath = java.nio.file.Paths.get(System.getProperty("user.home"), ".aws", "public_key.pem");
                boolean hasPublicKey = java.nio.file.Files.exists(publicKeyPath);

                requestItem.setEnabled(profile != null);
                showItem.setEnabled(hasCredentials);
                showEncryptedItem.setEnabled(hasCredentials && hasPublicKey);
                openConsoleItem.setEnabled(hasCredentials);
                copyAccountItem.setEnabled(profile != null && hasAccountNumber(profile));
                pinItem.setText(profile != null && databaseManager.isProfilePinned(profile)
                    ? "Unpin Profile" : "Pin Profile");
                pinItem.setEnabled(profile != null);
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

    private boolean hasAccountNumber(String profileName) {
        String accountNumber = configManager.getAccountNumber(profileName);
        return accountNumber != null && !accountNumber.isBlank();
    }

    private void copyAccountNumberForProfile(String profileName) {
        if (profileName == null) {
            return;
        }
        String accountNumber = configManager.getAccountNumber(profileName);
        if (accountNumber == null || accountNumber.isBlank()) {
            statusLabel.setText("No account number configured for \"" + profileName + "\".");
            return;
        }
        try {
            StringSelection selection = new StringSelection(accountNumber.trim());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
            statusLabel.setText("Copied account number for \"" + profileName + "\" to clipboard.");
        } catch (Exception ex) {
            logger.error("Failed to copy account number to clipboard for profile: {}", profileName, ex);
            statusLabel.setText("Failed to copy account number: " + ex.getMessage());
        }
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

        CredentialsDialog dialog = new CredentialsDialog(this, credentials, showEncrypted, showPlaintext, databaseManager);
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

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                String loginUrl = AwsConsoleLauncher.buildLoginUrl(credentials);

                // A fresh WebDriver per click gives this console session its own cookie jar,
                // so it doesn't collide with sessions already open for other profiles (#169).
                WebDriver driver = WebDriverFactory.createWebDriver(configManager.getBrowserType(), true);
                openConsoleDrivers.add(driver);
                try {
                    driver.get(loginUrl);
                } catch (Exception e) {
                    openConsoleDrivers.remove(driver);
                    driver.quit();
                    throw e;
                }
                return null;
            }

            @Override
            protected void done() {
                openConsoleButton.setEnabled(true);
                openConsoleButton.setText("Open Console");

                try {
                    get();
                    statusLabel.setText("Opened AWS Console for profile: " + selectedProfile);
                } catch (Exception ex) {
                    statusLabel.setText("Failed to open AWS Console: " + ex.getMessage());
                    logger.error("Failed to open AWS Console for profile: {}", selectedProfile, ex);
                    if (!notifyIfHiddenInTray("Failed to open AWS Console (" + selectedProfile + ")", ex.getMessage(),
                            TrayIcon.MessageType.ERROR)) {
                        JOptionPane.showMessageDialog(SwingMain.this,
                            "Failed to open AWS Console: " + ex.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        };
        worker.execute();
    }

    private static class TokenStatusRow {
        private final String profile;
        private final String accountNumber;
        private final String status;
        private final String expiresAt;
        private final String timeRemaining;

        public TokenStatusRow(String profile, String accountNumber, String status, String expiresAt, String timeRemaining) {
            this.profile = profile;
            this.accountNumber = accountNumber;
            this.status = status;
            this.expiresAt = expiresAt;
            this.timeRemaining = timeRemaining;
        }

        public String getProfile() { return profile; }
        public String getAccountNumber() { return accountNumber; }
        public String getStatus() { return status; }
        public String getExpiresAt() { return expiresAt; }
        public String getTimeRemaining() { return timeRemaining; }
    }

    public static void main(String[] args) {
        System.setProperty("SE_AVOID_STATS", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        StartupLog startupLog = StartupLog.forCurrentUser();
        startupLog.begin(AboutDialog.resolveCurrentVersion());
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            logger.error("Uncaught exception on thread {}", thread.getName(), error);
            if (!startupLog.isFinished()) {
                startupLog.failed("startup (thread " + thread.getName() + ")", error);
            }
        });

        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception e) {
                    // Use default look and feel
                }

                try {
                    boolean firstRun = !ConfigManager.configFileExists();
                    if (firstRun) {
                        DatabaseManager.deleteIfExists();
                        FirstRunSetupDialog setup = new FirstRunSetupDialog();
                        setup.setVisible(true);
                        if (!setup.isSetupCompleted()) {
                            startupLog.cancelled("first-run setup was not completed");
                            System.exit(0);
                        }
                    }

                    SwingMain mainWindow = new SwingMain();
                    boolean startMinimized = mainWindow.trayIcon != null && mainWindow.databaseManager.getStartMinimizedToTray();
                    if (!startMinimized) {
                        mainWindow.setVisible(true);
                        syncWindowPositionWithWindowManager(mainWindow);
                    }
                    mainWindow.onStartupComplete(firstRun, !startMinimized);
                    startupLog.succeeded();
                    logger.info("Startup completed");
                } catch (Throwable t) {
                    reportStartupFailure(startupLog, "main window initialization", t);
                }
            });
        } catch (Throwable t) {
            reportStartupFailure(startupLog, "launch", t);
        }
    }

    /**
     * Records a failure to start in both logs and tells the user where to find the details, since
     * otherwise a failed launch just looks like the app never opened.
     */
    private static void reportStartupFailure(StartupLog startupLog, String phase, Throwable error) {
        startupLog.failed(phase, error);
        try {
            logger.error("Startup failed during {}", phase, error);
        } catch (Throwable ignored) {
            // The startup log already has the details; nothing more to do if logging itself is broken.
        }
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null,
                    "AWS IdP SAML UI failed to start:\n" + error + "\n\nDetails were written to:\n" + startupLog.getFile(),
                    "Startup Failed",
                    JOptionPane.ERROR_MESSAGE);
            }
        } catch (Throwable ignored) {
            // No usable display; the startup log is the record.
        }
        System.exit(1);
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
        // Moving a maximized window can un-maximize it on some window managers, and a maximized
        // window already sits at the screen origin the popups would be computed from.
        if (window instanceof Frame frame && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
            return;
        }
        Point location = window.getLocation();
        window.setLocation(location.x + 1, location.y);
        window.setLocation(location.x, location.y);
    }
}