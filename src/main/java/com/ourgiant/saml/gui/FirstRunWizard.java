package com.ourgiant.saml.gui;

import com.ourgiant.saml.core.ConfigManager;
import com.ourgiant.saml.core.CredentialManager;
import com.ourgiant.saml.core.CredentialRequestError;
import com.ourgiant.saml.core.DatabaseManager;
import com.ourgiant.saml.core.DiscoveredRoles;
import com.ourgiant.saml.core.PasswordManager;
import com.ourgiant.saml.core.ProfileBundle;
import com.ourgiant.saml.core.RegionChoices;
import com.ourgiant.saml.core.SamlAuthenticator;

import org.ini4j.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * First-run setup (#252), shown when ~/.aws/samlsts doesn't exist: Welcome → Identity provider →
 * AWS accounts (optional, #250) → Done. samlsts is written when the identity provider step is
 * saved, so the accounts step can sign in through it, and so quitting after that point leaves a
 * working setup that opens on the main window's "no accounts yet" panel (#253).
 */
public class FirstRunWizard extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(FirstRunWizard.class);

    private enum Step { WELCOME, PROVIDER, ACCOUNTS, DONE }

    private static final String[] STEP_NAMES = {"Welcome", "Identity provider", "AWS accounts", "Done"};
    private static final String[] BROWSERS = {"chrome", "firefox", "edge"};

    private final ConfigManager configManager = new ConfigManager(false);
    private final DatabaseManager databaseManager = new DatabaseManager();

    private Step step = Step.WELCOME;
    private boolean configSaved;
    private boolean setupCompleted;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JLabel[] stepLabels = new JLabel[STEP_NAMES.length];
    private final JLabel stepCounter = new JLabel();
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton backButton = new JButton("‹ Back");
    private final JButton skipButton = new JButton("Skip");
    private final JButton nextButton = new JButton();

    // Welcome
    private final JRadioButton scratchRadio = new JRadioButton("Set up from scratch", true);
    private final JRadioButton bundleRadio = new JRadioButton("Import a profile file from my team");

    // Identity provider
    private final IdentityProviderForm providerForm = new IdentityProviderForm(configManager, null);
    private final JLabel providerNote = new JLabel();
    private final JTextField usernameField = new JTextField(28);
    private final JComboBox<String> browserCombo = new JComboBox<>(BROWSERS);
    private final JCheckBox showBrowserCheckBox = new JCheckBox("Show browser");
    private final JButton testButton = new JButton("Test Sign-in...");
    private final JButton cancelTestButton = new JButton("Cancel");
    private final JProgressBar testProgress = new JProgressBar();
    private final JLabel testStatus = new JLabel(" ");
    private volatile SamlAuthenticator testAuthenticator;
    private DiscoveredRoles discovered;
    private String discoveredFor;

    // AWS accounts
    private final JLabel accountsNote = new JLabel(" ");
    private final JRadioButton importRadio = new JRadioButton();
    private final JLabel importDetail = new JLabel();
    private final JRadioButton manualRadio = new JRadioButton("Add one account by hand");
    private final JRadioButton skipRadio = new JRadioButton("Skip for now");

    // Done
    private final JPanel summaryPanel = new JPanel(new GridBagLayout());
    private JComboBox<RegionChoices.Choice> regionCombo;
    private JSpinner durationSpinner;

    public FirstRunWizard() {
        super((Frame) null, "Welcome to AWS IDP SAML Client", true);
        initializeUI();
        show(Step.WELCOME);
        pack();
        setMinimumSize(new Dimension(640, getHeight()));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onCancel();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                providerForm.cancelDetection();
                databaseManager.close();
            }
        });
    }

    /** True if setup got far enough to open the main window: an identity provider (or imported profiles) was saved. */
    public boolean isSetupCompleted() {
        return setupCompleted;
    }

    // ---- layout ---------------------------------------------------------------------------

    private void initializeUI() {
        setLayout(new BorderLayout());

        JPanel stepsRow = new JPanel(new GridLayout(1, STEP_NAMES.length));
        stepsRow.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor()));
        for (int i = 0; i < STEP_NAMES.length; i++) {
            stepLabels[i] = new JLabel(STEP_NAMES[i], SwingConstants.CENTER);
            stepLabels[i].setBorder(BorderFactory.createEmptyBorder(9, 6, 9, 6));
            stepsRow.add(stepLabels[i]);
        }
        add(stepsRow, BorderLayout.NORTH);

        cardPanel.add(buildWelcomeCard(), Step.WELCOME.name());
        cardPanel.add(buildProviderCard(), Step.PROVIDER.name());
        cardPanel.add(buildAccountsCard(), Step.ACCOUNTS.name());
        cardPanel.add(buildDoneCard(), Step.DONE.name());
        add(cardPanel, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(6, 16, 12, 16));
        stepCounter.setForeground(UIManager.getColor("Label.disabledForeground"));
        footer.add(stepCounter, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        cancelButton.addActionListener(e -> onCancel());
        backButton.setMnemonic(KeyEvent.VK_B);
        backButton.addActionListener(e -> onBack());
        skipButton.setMnemonic(KeyEvent.VK_K);
        skipButton.setToolTipText("Finish setup without adding any AWS accounts now");
        skipButton.addActionListener(e -> show(Step.DONE));
        nextButton.addActionListener(e -> onNext());
        buttons.add(cancelButton);
        buttons.add(backButton);
        buttons.add(skipButton);
        buttons.add(nextButton);
        footer.add(buttons, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(nextButton);
    }

    private static Color separatorColor() {
        Color color = UIManager.getColor("Component.borderColor");
        return color != null ? color : Color.LIGHT_GRAY;
    }

    private static JPanel card() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 8, 20));
        return panel;
    }

    private static GridBagConstraints row(int y) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = y;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(0, 0, 8, 0);
        return gbc;
    }

    private static JLabel lead(String text) {
        JLabel label = new JLabel(text);
        label.putClientProperty("FlatLaf.styleClass", "h3");
        return label;
    }

    private static JLabel wrapped(String text) {
        return new JLabel("<html><body style='width: 540px'>" + text + "</body></html>");
    }

    /** A radio choice with a line of explanation under it, indented to line up with the radio's text. */
    private static JPanel choice(JRadioButton radio, JLabel detail) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        radio.setFont(radio.getFont().deriveFont(Font.BOLD));
        panel.add(radio, BorderLayout.NORTH);
        detail.setBorder(BorderFactory.createEmptyBorder(0, 26, 0, 0));
        detail.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(detail, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildWelcomeCard() {
        JPanel panel = card();
        int y = 0;
        panel.add(lead("Let's connect to your identity provider"), row(y++));
        panel.add(wrapped("This app signs in to your company's identity provider (Okta, Azure AD, ADFS, Ping, OneLogin "
            + "or any SAML 2.0 provider) and turns that sign-in into temporary AWS credentials. Setup takes a couple of minutes."), row(y++));
        ButtonGroup group = new ButtonGroup();
        group.add(scratchRadio);
        group.add(bundleRadio);
        scratchRadio.setMnemonic(KeyEvent.VK_S);
        bundleRadio.setMnemonic(KeyEvent.VK_I);
        GridBagConstraints gbc = row(y++);
        gbc.insets = new Insets(8, 0, 0, 0);
        panel.add(choice(scratchRadio, new JLabel("Enter your identity provider's details, then pick the AWS accounts you use.")), gbc);
        panel.add(choice(bundleRadio, new JLabel("Use a profile file (.ini) exported with File → Export Profiles. "
            + "It never contains credentials or passwords.")), row(y++));
        scratchRadio.addActionListener(e -> updateButtons());
        bundleRadio.addActionListener(e -> updateButtons());
        GridBagConstraints fill = row(y);
        fill.weighty = 1.0;
        panel.add(Box.createGlue(), fill);
        return panel;
    }

    private JPanel buildProviderCard() {
        JPanel panel = card();
        int y = 0;
        panel.add(lead("Your identity provider"), row(y++));
        providerNote.setVisible(false);
        panel.add(providerNote, row(y++));

        usernameField.setToolTipText("The username entered on your identity provider's login page");
        providerForm.addRow(new JLabel("Username:"), KeyEvent.VK_E, usernameField);
        browserCombo.setToolTipText("Browser used to drive the sign-in");
        providerForm.addRow(new JLabel("Browser:"), KeyEvent.VK_W, browserCombo);
        providerForm.setBrowserType(() -> (String) browserCombo.getSelectedItem());
        providerForm.setShowBrowser(showBrowserCheckBox::isSelected);
        providerForm.setOnResize(() -> {
            if (isShowing()) {
                pack();
            }
        });
        GridBagConstraints formGbc = row(y++);
        formGbc.insets = new Insets(0, -6, 4, -6); // the form's own cell insets line it up with the text above
        panel.add(providerForm, formGbc);

        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        testButton.setMnemonic(KeyEvent.VK_T);
        testButton.setToolTipText("Save these details and sign in once to check them. The AWS roles found are "
            + "offered in the next step, so you won't have to sign in again.");
        testButton.addActionListener(e -> testSignIn());
        showBrowserCheckBox.setToolTipText("Show the browser window for Test Sign-in and Detect, e.g. to complete an MFA prompt");
        cancelTestButton.addActionListener(e -> cancelTest());
        cancelTestButton.setVisible(false);
        testProgress.setIndeterminate(true);
        testProgress.setPreferredSize(new Dimension(100, 12));
        testProgress.setVisible(false);
        testRow.add(testButton);
        testRow.add(showBrowserCheckBox);
        testRow.add(cancelTestButton);
        testRow.add(testProgress);
        GridBagConstraints testGbc = row(y++);
        testGbc.insets = new Insets(6, -6, 2, 0);
        panel.add(testRow, testGbc);
        JLabel optional = IdentityProviderForm.hint("Optional. Signs in once to check these details.");
        optional.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(optional, row(y++));
        panel.add(testStatus, row(y++));
        return panel;
    }

    private JPanel buildAccountsCard() {
        JPanel panel = card();
        int y = 0;
        JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        title.add(lead("Add the AWS accounts you sign in to"));
        JLabel optional = new JLabel("Optional");
        optional.putClientProperty("FlatLaf.style", "font: -1; foreground: $Label.disabledForeground");
        optional.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(2, 10, 0, 0),
            BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(separatorColor()),
                BorderFactory.createEmptyBorder(0, 6, 0, 6))));
        title.add(optional);
        panel.add(title, row(y++));
        panel.add(new JLabel("You can do this now, or later from File → Manage Profiles or the main window."), row(y++));
        panel.add(accountsNote, row(y++));

        ButtonGroup group = new ButtonGroup();
        for (JRadioButton radio : new JRadioButton[]{importRadio, manualRadio, skipRadio}) {
            group.add(radio);
            radio.addActionListener(e -> updateButtons());
        }
        importRadio.setSelected(true);
        importRadio.setMnemonic(KeyEvent.VK_I);
        manualRadio.setMnemonic(KeyEvent.VK_A);
        skipRadio.setMnemonic(KeyEvent.VK_S);
        GridBagConstraints gbc = row(y++);
        gbc.insets = new Insets(6, 0, 0, 0);
        panel.add(choice(importRadio, importDetail), gbc);
        panel.add(choice(manualRadio, new JLabel("Enter an account number and IAM role name.")), row(y++));
        panel.add(choice(skipRadio, new JLabel("Finish setup without any accounts. The main window shows how to add them.")), row(y++));
        GridBagConstraints fill = row(y);
        fill.weighty = 1.0;
        panel.add(Box.createGlue(), fill);
        return panel;
    }

    private JPanel buildDoneCard() {
        JPanel panel = card();
        int y = 0;
        panel.add(lead("You're set up"), row(y++));
        panel.add(summaryPanel, row(y++));

        JPanel defaults = new JPanel(new GridBagLayout());
        defaults.setBorder(BorderFactory.createTitledBorder("Defaults (change any time in File → Configuration)"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.EAST;
        JLabel regionLabel = new JLabel("Default region:");
        regionLabel.setDisplayedMnemonic(KeyEvent.VK_R);
        defaults.add(regionLabel, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        regionCombo = new JComboBox<>(RegionChoices.ALL.toArray(new RegionChoices.Choice[0]));
        regionCombo.setMaximumRowCount(16);
        regionCombo.setToolTipText("The region for profiles that don't choose their own. Type a letter to jump.");
        regionLabel.setLabelFor(regionCombo);
        defaults.add(regionCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        JLabel durationLabel = new JLabel("Session duration (minutes):");
        durationLabel.setDisplayedMnemonic(KeyEvent.VK_D);
        defaults.add(durationLabel, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        int min = databaseManager.getMinDuration() / 60;
        int max = databaseManager.getMaxDuration() / 60;
        int current = Math.max(min, Math.min(max, databaseManager.getSessionDuration() / 60));
        durationSpinner = new JSpinner(new SpinnerNumberModel(current, min, max, 15));
        durationSpinner.setToolTipText("How long requested AWS credentials stay valid");
        durationLabel.setLabelFor(durationSpinner);
        defaults.add(durationSpinner, gbc);

        GridBagConstraints defaultsGbc = row(y++);
        defaultsGbc.insets = new Insets(10, 0, 0, 0);
        panel.add(defaults, defaultsGbc);
        GridBagConstraints fill = row(y);
        fill.weighty = 1.0;
        panel.add(Box.createGlue(), fill);
        return panel;
    }

    // ---- navigation -----------------------------------------------------------------------

    private void show(Step next) {
        step = next;
        switch (next) {
            case PROVIDER -> enterProviderStep();
            case ACCOUNTS -> enterAccountsStep();
            case DONE -> enterDoneStep();
            default -> { }
        }
        cards.show(cardPanel, next.name());
        for (int i = 0; i < stepLabels.length; i++) {
            JLabel label = stepLabels[i];
            boolean current = i == next.ordinal();
            label.setText((i < next.ordinal() ? "✓ " : "") + STEP_NAMES[i]);
            label.setFont(label.getFont().deriveFont(current ? Font.BOLD : Font.PLAIN));
            label.setForeground(current ? accentColor()
                : UIManager.getColor(i < next.ordinal() ? "Label.foreground" : "Label.disabledForeground"));
            label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, current ? accentColor() : new Color(0, 0, 0, 0)),
                BorderFactory.createEmptyBorder(9, 6, 7, 6)));
        }
        stepCounter.setText("Step " + (next.ordinal() + 1) + " of " + STEP_NAMES.length);
        updateButtons();
    }

    private static Color accentColor() {
        Color color = UIManager.getColor("Component.accentColor");
        return color != null ? color : new Color(0x2675BF);
    }

    private void updateButtons() {
        boolean testing = testAuthenticator != null;
        backButton.setVisible(step != Step.WELCOME);
        // Once the provider is saved, going back to Welcome would only offer to start over.
        backButton.setEnabled(!testing && !(step == Step.PROVIDER && configSaved));
        skipButton.setVisible(step == Step.ACCOUNTS);
        nextButton.setEnabled(!testing);
        cancelButton.setEnabled(!testing);
        nextButton.setText(switch (step) {
            case WELCOME -> bundleRadio.isSelected() ? "Choose File..." : "Next ›";
            case PROVIDER -> "Save & Next ›";
            case ACCOUNTS -> importRadio.isSelected() ? (hasDiscoveredRoles() ? "Choose Roles..." : "Sign In & Find Roles...")
                : manualRadio.isSelected() ? "Add Account..." : "Next ›";
            case DONE -> "Open AWS IDP SAML Client";
        });
    }

    private void onNext() {
        switch (step) {
            case WELCOME -> {
                if (scratchRadio.isSelected() || importProfileFile()) {
                    show(Step.PROVIDER);
                }
            }
            case PROVIDER -> {
                if (saveProvider()) {
                    show(Step.ACCOUNTS);
                }
            }
            case ACCOUNTS -> {
                if (importRadio.isSelected()) {
                    importFromProvider();
                } else if (manualRadio.isSelected()) {
                    addProfileByHand();
                } else {
                    show(Step.DONE);
                }
            }
            case DONE -> finish();
        }
    }

    private void onBack() {
        switch (step) {
            case PROVIDER -> show(Step.WELCOME);
            case ACCOUNTS -> show(Step.PROVIDER);
            case DONE -> show(Step.ACCOUNTS);
            default -> { }
        }
    }

    private void onCancel() {
        if (testAuthenticator != null) {
            return;
        }
        if (!configSaved) {
            int choice = JOptionPane.showConfirmDialog(this,
                "The app needs an identity provider to work, so cancelling setup now will exit.\nCancel setup?",
                "Cancel Setup", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                setupCompleted = false;
                dispose();
            }
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
            "Your identity provider is saved. Close setup and open the app?\nYou can add AWS accounts from the main window.",
            "Close Setup", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            setupCompleted = true;
            dispose();
        }
    }

    // ---- step 1: profile file -------------------------------------------------------------

    /** Runs the usual profile-file import; true if anything was imported. */
    private boolean importProfileFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import a Profile File");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Profile exports (*.ini)", "ini"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        return importProfileFile(chooser.getSelectedFile().toPath());
    }

    /** As {@link #importProfileFile()}, for a given file (separate so it can be driven directly). */
    boolean importProfileFile(Path file) {
        ProfileBundle bundle;
        try {
            bundle = ProfileBundle.read(file);
        } catch (java.io.IOException ex) {
            logger.warn("Can't import profiles from {}", file, ex);
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Can't Import", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (bundle.profiles().isEmpty()) {
            JOptionPane.showMessageDialog(this, file.getFileName() + " doesn't contain any profiles.",
                "Nothing To Import", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        boolean createdForImport = false;
        try {
            if (!ConfigManager.configFileExists()) {
                Map<String, Map<String, String>> sections = new LinkedHashMap<>();
                sections.put("global", new LinkedHashMap<>());
                configManager.createConfig(sections);
                createdForImport = true;
            }
        } catch (Exception ex) {
            logger.error("Failed to create samlsts for import", ex);
            JOptionPane.showMessageDialog(this, "Failed to write the configuration: " + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        ProfileImportDialog dialog = new ProfileImportDialog(null, configManager, databaseManager, bundle, file);
        dialog.setVisible(true);
        if (dialog.getResult() == null) {
            if (createdForImport) {
                deleteConfigFile();
            }
            return false;
        }

        configSaved = true;
        int count = dialog.getResult().importedProfiles().size();
        List<String> providers = configManager.getIdpProviders();
        if (!providers.isEmpty()) {
            String section = configManager.getDefaultProvider();
            if (section == null || !providers.contains(section)) {
                section = providers.get(0);
            }
            providerForm.setOriginalSection(section);
            providerForm.loadFrom(section, ConfigManager.awsProviderName(section));
        }
        providerNote.setText("<html><body style='width: 540px'>Imported " + count + (count == 1 ? " profile" : " profiles")
            + " from " + escape(file.getFileName().toString()) + ". Check the identity provider details and enter "
            + "your username.</body></html>");
        providerNote.setVisible(true);
        return true;
    }

    private static void deleteConfigFile() {
        try {
            Files.deleteIfExists(Paths.get(System.getProperty("user.home"), ".aws", "samlsts"));
        } catch (Exception ex) {
            logger.warn("Could not remove the samlsts written for a cancelled import", ex);
        }
    }

    // ---- step 2: identity provider -------------------------------------------------------

    private void enterProviderStep() {
        if (usernameField.getText().isBlank()) {
            String username = configManager.getDefaultUsername();
            usernameField.setText(username != null ? username : "");
        }
        browserCombo.setSelectedItem(configManager.getBrowserType());
    }

    /**
     * Validates and saves step 2: writes samlsts if it doesn't exist yet, saves the provider
     * (renaming it if it was saved before under another name), the username and browser, and
     * makes the provider the default if there isn't one.
     */
    private boolean saveProvider() {
        if (!providerForm.validateInput(this)) {
            return false;
        }
        String username = usernameField.getText().trim();
        if (username.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter the username you sign in to your identity provider with.",
                "Username Required", JOptionPane.WARNING_MESSAGE);
            usernameField.requestFocusInWindow();
            return false;
        }
        try {
            if (!ConfigManager.configFileExists()) {
                Map<String, Map<String, String>> sections = new LinkedHashMap<>();
                sections.put("global", new LinkedHashMap<>(Map.of("username", username)));
                configManager.createConfig(sections);
            }
            String section = providerForm.save();
            configManager.saveGlobalSettings(username, configManager.getExplicitAwsRegion(null));
            String current = configManager.getDefaultProvider();
            if (current == null || !configManager.getIdpProviders().contains(current)) {
                configManager.setDefaultProvider(section);
            }
            databaseManager.setBrowser((String) browserCombo.getSelectedItem());
            configSaved = true;
            updateButtons();
            logger.info("First-run setup saved identity provider {}", section);
            return true;
        } catch (Exception ex) {
            logger.error("Failed to save the identity provider during first-run setup", ex);
            JOptionPane.showMessageDialog(this, "Failed to save: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private SamlAuthenticator newAuthenticator() {
        return new SamlAuthenticator(configManager, new CredentialManager(), new PasswordManager(databaseManager));
    }

    /** Saves step 2, then signs in once and keeps the roles found for step 3. */
    private void testSignIn() {
        if (!saveProvider()) {
            return;
        }
        String section = providerForm.getOriginalSection();
        SamlAuthenticator authenticator = newAuthenticator();
        testAuthenticator = authenticator;
        setTesting(true);
        testStatus.setForeground(UIManager.getColor("Label.foreground"));
        testStatus.setText("Signing in to " + ConfigManager.awsProviderName(section) + "...");
        boolean showBrowser = showBrowserCheckBox.isSelected();
        boolean fastPass = databaseManager.getFastPassEnabled();

        new SwingWorker<DiscoveredRoles, Void>() {
            @Override
            protected DiscoveredRoles doInBackground() throws Exception {
                return authenticator.discoverRoles(section, fastPass, showBrowser,
                    msg -> SwingUtilities.invokeLater(() -> testStatus.setText(msg)));
            }

            @Override
            protected void done() {
                boolean cancelled = testAuthenticator == null;
                testAuthenticator = null;
                setTesting(false);
                if (cancelled) {
                    testStatus.setText("Sign-in cancelled.");
                    return;
                }
                try {
                    DiscoveredRoles result = get();
                    discovered = result;
                    discoveredFor = section;
                    int count = result.roles().size();
                    testStatus.setText("✓ Signed in. " + count + (count == 1 ? " AWS role" : " AWS roles")
                        + " found; you can choose them in the next step.");
                    updateButtons();
                } catch (Exception ex) {
                    CredentialRequestError error = CredentialRequestError.classify(ex);
                    Color red = UIManager.getColor("Component.error.focusedBorderColor");
                    testStatus.setForeground(red != null ? red : Color.RED);
                    testStatus.setText(error.statusMessage());
                    JOptionPane.showMessageDialog(FirstRunWizard.this, error.htmlMessage(), error.title(), error.iconMessageType());
                }
            }
        }.execute();
    }

    private void cancelTest() {
        SamlAuthenticator authenticator = testAuthenticator;
        testAuthenticator = null;
        if (authenticator != null) {
            testStatus.setText("Cancelling sign-in...");
            authenticator.cancel();
        }
    }

    private void setTesting(boolean testing) {
        testButton.setEnabled(!testing);
        showBrowserCheckBox.setEnabled(!testing);
        cancelTestButton.setVisible(testing);
        testProgress.setVisible(testing);
        updateButtons();
    }

    private boolean hasDiscoveredRoles() {
        return discovered != null && discoveredFor != null && discoveredFor.equals(providerForm.getOriginalSection());
    }

    // ---- step 3: AWS accounts ------------------------------------------------------------

    private String provider() {
        return providerForm.getOriginalSection();
    }

    private void enterAccountsStep() {
        String name = ConfigManager.awsProviderName(provider());
        importRadio.setText("Import from " + name + "  (recommended)");
        importDetail.setText(hasDiscoveredRoles()
            ? "Your sign-in test found " + discovered.roles().size() + " roles. Tick the ones you use."
            : "Sign in once and choose from every AWS role you have. No account numbers or role names to look up.");
        List<String> profiles = configManager.getAvailableProfiles();
        accountsNote.setText(profiles.isEmpty() ? " "
            : "<html>You have " + profiles.size() + (profiles.size() == 1 ? " profile" : " profiles") + " so far: "
                + escape(IdentityProvidersDialog.summarize(profiles)) + "</html>");
    }

    private void importFromProvider() {
        String section = provider();
        ImportRolesDialog dialog = new ImportRolesDialog(null, configManager, this::newAuthenticator,
            databaseManager.getFastPassEnabled(), showBrowserCheckBox.isSelected());
        dialog.selectProvider(section);
        if (hasDiscoveredRoles()) {
            dialog.showRoles(section, discovered.roles(), discovered.accountAliases());
        }
        dialog.setVisible(true);
        if (dialog.getImportedCount() > 0) {
            show(Step.DONE);
        }
    }

    private void addProfileByHand() {
        ProfileEditDialog dialog = new ProfileEditDialog(null, configManager, null);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            show(Step.DONE);
        }
    }

    // ---- step 4: done --------------------------------------------------------------------

    private void enterDoneStep() {
        summaryPanel.removeAll();
        String section = provider();
        Profile.Section providerSection = configManager.getProvider(section);
        String host = providerSection != null ? IdentityProvidersDialog.host(providerSection.getOrDefault("loginpage", "")) : "";
        addSummaryLine(0, true, "Identity provider <b>" + escape(ConfigManager.awsProviderName(section)) + "</b> ("
            + escape(host) + ") for " + escape(usernameField.getText().trim()));
        List<String> profiles = configManager.getAvailableProfiles();
        if (profiles.isEmpty()) {
            addSummaryLine(1, false, "No AWS accounts yet. You can add them from the main window.");
        } else {
            addSummaryLine(1, true, "<b>" + profiles.size() + (profiles.size() == 1 ? " profile" : " profiles") + "</b>: "
                + escape(IdentityProvidersDialog.summarize(profiles)));
        }
        String region = configManager.getExplicitAwsRegion(null);
        RegionChoices.Choice choice = RegionChoices.find(region != null ? region : "us-east-1");
        if (choice == null) {
            choice = new RegionChoices.Choice(region, "not in list");
            regionCombo.addItem(choice);
        }
        regionCombo.setSelectedItem(choice);
        summaryPanel.revalidate();
    }

    private void addSummaryLine(int y, boolean done, String html) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = y; gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(2, 0, 2, 8);
        JLabel mark = new JLabel(done ? "✓" : "–");
        if (!done) {
            mark.setForeground(UIManager.getColor("Label.disabledForeground"));
        }
        summaryPanel.add(mark, gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        summaryPanel.add(new JLabel("<html><body style='width: 500px'>" + html + "</body></html>"), gbc);
    }

    private void finish() {
        try {
            RegionChoices.Choice region = (RegionChoices.Choice) regionCombo.getSelectedItem();
            configManager.saveGlobalSettings(usernameField.getText().trim(), region != null ? region.id() : null);
            databaseManager.setSessionDuration((Integer) durationSpinner.getValue() * 60);
            setupCompleted = true;
            logger.info("First-run setup completed");
            dispose();
        } catch (Exception ex) {
            logger.error("Failed to save first-run defaults", ex);
            JOptionPane.showMessageDialog(this, "Failed to save: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
