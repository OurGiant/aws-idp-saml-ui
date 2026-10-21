package com.ourgiant.saml.gui;

import com.ourgiant.saml.core.ConfigManager;
import com.ourgiant.saml.core.IdpPresets;
import com.ourgiant.saml.core.SamlAuthenticator;

import org.ini4j.Profile;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The identity provider fields (#249): provider type preset, AWS provider name with a live ARN
 * preview, login page URL and title. Used by the Edit Identity Provider dialog and the first-run
 * wizard (#252), which adds its own rows below with {@link #addRow}.
 */
public class IdentityProviderForm extends JPanel {

    private final ConfigManager configManager;
    private String originalSection; // null until the provider exists in samlsts
    private List<String> usedBy;

    private final JLabel presetLabel = new JLabel("Provider type:");
    private final JComboBox<IdpPresets.Preset> presetCombo = new JComboBox<>(IdpPresets.ALL.toArray(new IdpPresets.Preset[0]));
    private final JTextField nameField = new JTextField(28);
    private final JLabel arnPreview = new JLabel(" ");
    private final JTextField loginPageField = new JTextField(36);
    private final JLabel loginPageCheck = new JLabel(" ");
    private final JTextField loginTitleField = new JTextField(28);
    private final JLabel renameWarning = new JLabel();
    private Consumer<String> onSectionChange = s -> { };
    private Runnable onResize = () -> { };
    private int nextRow;

    /** @param originalSection the provider being edited, or null for a new one (which shows the preset list) */
    public IdentityProviderForm(ConfigManager configManager, String originalSection) {
        super(new GridBagLayout());
        this.configManager = configManager;
        setOriginalSection(originalSection);
        initializeUI();
        if (originalSection == null) {
            applyPreset();
        } else {
            loadFrom(originalSection, ConfigManager.awsProviderName(originalSection));
        }
        updateFeedback();
    }

    /** Called with the section name the provider will be saved as, e.g. {@code Fed-Okta}, as it's typed. */
    public void setOnSectionChange(Consumer<String> listener) {
        this.onSectionChange = listener;
        updateFeedback();
    }

    /** Called when the form's preferred height changes (the rename warning appeared or went away). */
    public void setOnResize(Runnable listener) {
        this.onResize = listener;
    }

    /** Treats the provider as existing under {@code section} from now on (e.g. after the wizard saves it). */
    public void setOriginalSection(String section) {
        this.originalSection = section;
        this.usedBy = section == null ? List.of() : configManager.getProfilesUsingProvider(section);
        presetLabel.setVisible(section == null);
        presetCombo.setVisible(section == null);
    }

    public String getOriginalSection() {
        return originalSection;
    }

    public JTextField nameField() {
        return nameField;
    }

    private void initializeUI() {
        presetCombo.setToolTipText("Fills in the fields below with a starting point for this kind of identity provider");
        presetCombo.addActionListener(e -> applyPreset());
        addRow(presetLabel, KeyEvent.VK_T, presetCombo);

        nameField.setToolTipText("The SAML provider name in IAM (IAM → Identity providers). It must match exactly.");
        addRow(new JLabel("AWS provider name:"), KeyEvent.VK_N, nameField);
        addHelp(hint("Must match the SAML provider name in IAM. Your AWS admin can tell you what it is."));
        arnPreview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, arnPreview.getFont().getSize() - 1));
        arnPreview.setForeground(UIManager.getColor("Label.disabledForeground"));
        addHelp(arnPreview);

        loginPageField.setToolTipText("The SAML-initiated login URL your browser is sent to first");
        addRow(new JLabel("Login page URL:"), KeyEvent.VK_U, loginPageField);
        addHelp(loginPageCheck);

        loginTitleField.setToolTipText("The browser page title that shows the login page has loaded");
        addRow(new JLabel("Login page title:"), KeyEvent.VK_L, loginTitleField);

        renameWarning.setVisible(false);
        renameWarning.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(warningColor()),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 1000; gbc.gridwidth = 2; // always last, below any rows added later
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.insets = new Insets(10, 6, 4, 6);
        add(renameWarning, gbc);

        DocumentListener onChange = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateFeedback(); }
            public void removeUpdate(DocumentEvent e) { updateFeedback(); }
            public void changedUpdate(DocumentEvent e) { updateFeedback(); }
        };
        nameField.getDocument().addDocumentListener(onChange);
        loginPageField.getDocument().addDocumentListener(onChange);
    }

    /** Adds a labelled row, lined up with the provider fields. */
    public void addRow(JLabel label, int mnemonic, JComponent field) {
        label.setDisplayedMnemonic(mnemonic);
        label.setLabelFor(field);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = nextRow;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.insets = new Insets(4, 6, 4, 6);
        add(label, gbc);
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.WEST;
        add(field, gbc);
        nextRow++;
    }

    /** Adds a line of help text under the field column. */
    public void addHelp(JComponent help) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 1; gbc.gridy = nextRow;
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 8, 2, 6);
        add(help, gbc);
        nextRow++;
    }

    static JLabel hint(String text) {
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

    /** Fills the fields from an existing provider section, under {@code name}. */
    public void loadFrom(String section, String name) {
        Profile.Section provider = configManager.getProvider(section);
        nameField.setText(name);
        if (provider != null) {
            loginPageField.setText(provider.getOrDefault("loginpage", ""));
            loginTitleField.setText(provider.getOrDefault("logintitle", ""));
        }
    }

    /** Live ARN preview, URL check, rename warning and section name. */
    private void updateFeedback() {
        String name = nameField.getText().trim();
        arnPreview.setText("→ arn:aws:iam::<account>:saml-provider/" + (name.isEmpty() ? "…" : name));
        onSectionChange.accept(name.isEmpty() ? null : ConfigManager.providerSection(name));

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

        boolean wasVisible = renameWarning.isVisible();
        boolean renaming = originalSection != null && !name.isEmpty()
            && !ConfigManager.providerSection(name).equals(originalSection);
        if (renaming && !usedBy.isEmpty()) {
            renameWarning.setText("<html><body style='width: 380px'><b>Renaming updates " + usedBy.size()
                + (usedBy.size() == 1 ? " profile" : " profiles")
                + "</b> that use this provider. The new name must match the SAML provider in IAM, "
                + "or their credential requests will fail.</html>");
            renameWarning.setVisible(true);
        } else {
            renameWarning.setVisible(false);
        }
        if (wasVisible != renameWarning.isVisible()) {
            onResize.run();
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

    /**
     * Checks the fields and, if something's wrong, tells the user and focuses the field.
     *
     * @return true if the provider can be saved
     */
    public boolean validateInput(Component parent) {
        String nameProblem = configManager.validateProviderName(nameField.getText().trim(), originalSection);
        if (nameProblem != null) {
            return warn(parent, nameProblem, "AWS Provider Name", nameField);
        }
        String urlProblem = urlProblem(loginPageField.getText().trim());
        if (urlProblem != null) {
            return warn(parent, urlProblem, "Login Page URL", loginPageField);
        }
        if (loginTitleField.getText().trim().isEmpty()) {
            return warn(parent, "Enter the login page title.", "Login Page Title", loginTitleField);
        }
        return true;
    }

    private static boolean warn(Component parent, String message, String title, JComponent focus) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.WARNING_MESSAGE);
        focus.requestFocusInWindow();
        return false;
    }

    /**
     * Saves the provider (adding it, or updating/renaming the original), then treats it as the
     * original from now on. Call {@link #validateInput} first.
     *
     * @return the saved section, e.g. {@code Fed-Okta}
     */
    public String save() throws Exception {
        String name = nameField.getText().trim();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("loginpage", loginPageField.getText().trim());
        fields.put("logintitle", loginTitleField.getText().trim());
        configManager.saveProvider(originalSection, name, fields);
        String section = ConfigManager.providerSection(name);
        setOriginalSection(section);
        return section;
    }
}
