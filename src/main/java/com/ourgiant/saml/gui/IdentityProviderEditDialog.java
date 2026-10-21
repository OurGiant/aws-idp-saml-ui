package com.ourgiant.saml.gui;

import com.ourgiant.saml.core.ConfigManager;
import com.ourgiant.saml.core.IdpPresets;
import com.ourgiant.saml.core.SamlAuthenticator;

import org.ini4j.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds, edits or duplicates one identity provider ({@code [Fed-*]} section in ~/.aws/samlsts) (#249).
 * The name typed here is the IAM SAML provider name; the {@code Fed-} prefix is added on save.
 */
public class IdentityProviderEditDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(IdentityProviderEditDialog.class);

    private final ConfigManager configManager;
    private final String originalSection; // null when adding or duplicating
    private final List<String> usedBy;

    private final JComboBox<IdpPresets.Preset> presetCombo = new JComboBox<>(IdpPresets.ALL.toArray(new IdpPresets.Preset[0]));
    private final JTextField nameField = new JTextField(28);
    private final JLabel arnPreview = new JLabel(" ");
    private final JTextField loginPageField = new JTextField(36);
    private final JLabel loginPageCheck = new JLabel(" ");
    private final JTextField loginTitleField = new JTextField(28);
    private final JLabel renameWarning = new JLabel();
    private final JLabel sectionLabel = new JLabel(" ");

    private String savedSection;

    /** Add a provider. */
    public static IdentityProviderEditDialog forAdd(Window parent, ConfigManager configManager) {
        IdentityProviderEditDialog dialog = new IdentityProviderEditDialog(parent, configManager, null, "Add Identity Provider");
        dialog.applyPreset();
        return dialog;
    }

    /** Edit {@code section}, including renaming it. */
    public static IdentityProviderEditDialog forEdit(Window parent, ConfigManager configManager, String section) {
        IdentityProviderEditDialog dialog = new IdentityProviderEditDialog(parent, configManager, section,
            "Edit Identity Provider: " + ConfigManager.awsProviderName(section));
        dialog.loadFrom(section, ConfigManager.awsProviderName(section));
        return dialog;
    }

    /** Add a provider pre-filled from {@code section} under {@code suggestedName}. */
    public static IdentityProviderEditDialog forDuplicate(Window parent, ConfigManager configManager, String section,
                                                          String suggestedName) {
        IdentityProviderEditDialog dialog = new IdentityProviderEditDialog(parent, configManager, null, "Duplicate Identity Provider");
        dialog.loadFrom(section, suggestedName);
        dialog.nameField.selectAll();
        return dialog;
    }

    private IdentityProviderEditDialog(Window parent, ConfigManager configManager, String originalSection, String title) {
        super(parent, title, ModalityType.APPLICATION_MODAL);
        this.configManager = configManager;
        this.originalSection = originalSection;
        this.usedBy = originalSection == null ? List.of() : configManager.getProfilesUsingProvider(originalSection);

        initializeUI();
        updateFeedback();
        pack();
        setMinimumSize(new Dimension(560, getHeight()));
        setLocationRelativeTo(parent);
    }

    /** The section saved, e.g. {@code Fed-Okta}; null if the dialog was cancelled. */
    public String getSavedSection() {
        return savedSection;
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        if (originalSection == null) {
            presetCombo.setToolTipText("Fills in the fields below with a starting point for this kind of identity provider");
            presetCombo.addActionListener(e -> applyPreset());
            row = addRow(form, gbc, row, "Provider type:", KeyEvent.VK_T, presetCombo);
        }

        nameField.setToolTipText("The SAML provider name in IAM (IAM → Identity providers). It must match exactly.");
        row = addRow(form, gbc, row, "AWS provider name:", KeyEvent.VK_N, nameField);
        row = addHelp(form, gbc, row, hint("Must match the SAML provider name in IAM. Your AWS admin can tell you what it is."));
        arnPreview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, arnPreview.getFont().getSize() - 1));
        arnPreview.setForeground(UIManager.getColor("Label.disabledForeground"));
        row = addHelp(form, gbc, row, arnPreview);

        loginPageField.setToolTipText("The SAML-initiated login URL your browser is sent to first");
        row = addRow(form, gbc, row, "Login page URL:", KeyEvent.VK_U, loginPageField);
        row = addHelp(form, gbc, row, loginPageCheck);

        loginTitleField.setToolTipText("The browser page title that shows the login page has loaded");
        row = addRow(form, gbc, row, "Login page title:", KeyEvent.VK_L, loginTitleField);

        renameWarning.setVisible(false);
        renameWarning.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(warningColor()),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.insets = new Insets(10, 6, 4, 6);
        form.add(renameWarning, gbc);

        add(form, BorderLayout.CENTER);

        DocumentListener onChange = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateFeedback(); }
            public void removeUpdate(DocumentEvent e) { updateFeedback(); }
            public void changedUpdate(DocumentEvent e) { updateFeedback(); }
        };
        nameField.getDocument().addDocumentListener(onChange);
        loginPageField.getDocument().addDocumentListener(onChange);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 12, 10, 12));
        sectionLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        bottom.add(sectionLabel, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> setVisible(false));
        JButton saveButton = new JButton("Save");
        saveButton.setMnemonic(KeyEvent.VK_S);
        saveButton.addActionListener(e -> onSave());
        buttons.add(cancelButton);
        buttons.add(saveButton);
        bottom.add(buttons, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(saveButton);
        getRootPane().registerKeyboardAction(e -> setVisible(false),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private static int addRow(JPanel form, GridBagConstraints gbc, int row, String label, int mnemonic, JComponent field) {
        JLabel jLabel = new JLabel(label);
        jLabel.setDisplayedMnemonic(mnemonic);
        jLabel.setLabelFor(field);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.insets = new Insets(4, 6, 4, 6);
        form.add(jLabel, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.WEST;
        form.add(field, gbc);
        return row + 1;
    }

    private static int addHelp(JPanel form, GridBagConstraints gbc, int row, JComponent help) {
        gbc.gridx = 1; gbc.gridy = row; gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 8, 2, 6);
        form.add(help, gbc);
        return row + 1;
    }

    private static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 1f));
        return label;
    }

    private static Color warningColor() {
        Color color = UIManager.getColor("Component.warning.focusedBorderColor");
        return color != null ? color : new Color(0xE2A53A);
    }

    private static Color errorColor() {
        Color color = UIManager.getColor("Component.error.focusedBorderColor");
        return color != null ? color : new Color(0xC2372E);
    }

    private void applyPreset() {
        IdpPresets.Preset preset = (IdpPresets.Preset) presetCombo.getSelectedItem();
        if (preset == null) {
            return;
        }
        nameField.setText(preset.suggestedName());
        loginPageField.setText(preset.loginPage());
        loginTitleField.setText(preset.loginTitle());
    }

    private void loadFrom(String section, String name) {
        Profile.Section provider = configManager.getProvider(section);
        nameField.setText(name);
        if (provider != null) {
            loginPageField.setText(provider.getOrDefault("loginpage", ""));
            loginTitleField.setText(provider.getOrDefault("logintitle", ""));
        }
    }

    /** Live ARN preview, URL check, rename warning and the section name in the footer. */
    private void updateFeedback() {
        String name = nameField.getText().trim();
        arnPreview.setText("→ arn:aws:iam::<account>:saml-provider/" + (name.isEmpty() ? "…" : name));
        sectionLabel.setText(name.isEmpty() ? " " : "Saved as [" + ConfigManager.providerSection(name) + "] in samlsts");

        String url = loginPageField.getText().trim();
        String urlProblem = urlProblem(url);
        if (url.isEmpty()) {
            loginPageCheck.setText(" ");
        } else if (urlProblem == null) {
            loginPageCheck.setForeground(UIManager.getColor("Label.foreground"));
            loginPageCheck.setText("✓ Secure (https) URL");
        } else {
            loginPageCheck.setForeground(errorColor());
            loginPageCheck.setText(urlProblem);
        }

        boolean renaming = originalSection != null && !name.isEmpty()
            && !ConfigManager.providerSection(name).equals(originalSection);
        boolean wasVisible = renameWarning.isVisible();
        if (renaming && !usedBy.isEmpty()) {
            renameWarning.setText("<html><body style='width: 380px'><b>Renaming updates " + usedBy.size() + (usedBy.size() == 1 ? " profile" : " profiles")
                + "</b> that use this provider. The new name must match the SAML provider in IAM, "
                + "or their credential requests will fail.</html>");
            renameWarning.setVisible(true);
        } else {
            renameWarning.setVisible(false);
        }
        if (isShowing() && wasVisible != renameWarning.isVisible()) {
            pack();
        }
    }

    private static String urlProblem(String url) {
        try {
            SamlAuthenticator.validateSecureLoginUrl(url);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private void onSave() {
        String name = nameField.getText().trim();
        String loginPage = loginPageField.getText().trim();
        String loginTitle = loginTitleField.getText().trim();

        String nameProblem = configManager.validateProviderName(name, originalSection);
        if (nameProblem != null) {
            warn(nameProblem, "AWS Provider Name", nameField);
            return;
        }
        String urlProblem = urlProblem(loginPage);
        if (urlProblem != null) {
            warn(urlProblem, "Login Page URL", loginPageField);
            return;
        }
        if (loginTitle.isEmpty()) {
            warn("Enter the login page title.", "Login Page Title", loginTitleField);
            return;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("loginpage", loginPage);
        fields.put("logintitle", loginTitle);
        try {
            configManager.saveProvider(originalSection, name, fields);
            savedSection = ConfigManager.providerSection(name);
            setVisible(false);
        } catch (Exception ex) {
            logger.error("Failed to save identity provider {}", name, ex);
            JOptionPane.showMessageDialog(this, "Failed to save the identity provider: " + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void warn(String message, String title, JComponent focus) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.WARNING_MESSAGE);
        focus.requestFocusInWindow();
    }
}
