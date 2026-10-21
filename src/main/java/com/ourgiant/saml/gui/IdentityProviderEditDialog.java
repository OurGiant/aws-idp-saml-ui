package com.ourgiant.saml.gui;

import com.ourgiant.saml.core.ConfigManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Adds, edits or duplicates one identity provider ({@code [Fed-*]} section in ~/.aws/samlsts) (#249).
 * The name typed here is the IAM SAML provider name; the {@code Fed-} prefix is added on save.
 */
public class IdentityProviderEditDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(IdentityProviderEditDialog.class);

    private final IdentityProviderForm form;
    private final JLabel sectionLabel = new JLabel(" ");
    private String savedSection;

    /** Add a provider. */
    public static IdentityProviderEditDialog forAdd(Window parent, ConfigManager configManager) {
        return new IdentityProviderEditDialog(parent, configManager, null, "Add Identity Provider");
    }

    /** Edit {@code section}, including renaming it. */
    public static IdentityProviderEditDialog forEdit(Window parent, ConfigManager configManager, String section) {
        return new IdentityProviderEditDialog(parent, configManager, section,
            "Edit Identity Provider: " + ConfigManager.awsProviderName(section));
    }

    /** Add a provider pre-filled from {@code section} under {@code suggestedName}. */
    public static IdentityProviderEditDialog forDuplicate(Window parent, ConfigManager configManager, String section,
                                                          String suggestedName) {
        IdentityProviderEditDialog dialog = new IdentityProviderEditDialog(parent, configManager, null, "Duplicate Identity Provider");
        dialog.form.loadFrom(section, suggestedName);
        dialog.form.nameField().selectAll();
        return dialog;
    }

    private IdentityProviderEditDialog(Window parent, ConfigManager configManager, String originalSection, String title) {
        super(parent, title, ModalityType.APPLICATION_MODAL);
        this.form = new IdentityProviderForm(configManager, originalSection);
        initializeUI();
        pack();
        setMinimumSize(new Dimension(560, getHeight()));
        setLocationRelativeTo(parent);
    }

    @Override
    public void setVisible(boolean visible) {
        if (!visible) {
            form.cancelDetection();
        }
        super.setVisible(visible);
    }

    /** The section saved, e.g. {@code Fed-Okta}; null if the dialog was cancelled. */
    public String getSavedSection() {
        return savedSection;
    }

    private void initializeUI() {
        setLayout(new BorderLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        add(form, BorderLayout.CENTER);
        sectionLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        form.setOnSectionChange(section -> sectionLabel.setText(section == null ? " " : "Saved as [" + section + "] in samlsts"));
        form.setOnResize(() -> {
            if (isShowing()) {
                pack();
            }
        });

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 12, 10, 12));
        bottom.add(sectionLabel, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> setVisible(false));
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                form.cancelDetection();
            }
        });
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

    private void onSave() {
        if (!form.validateInput(this)) {
            return;
        }
        try {
            savedSection = form.save();
            setVisible(false);
        } catch (Exception ex) {
            logger.error("Failed to save identity provider", ex);
            JOptionPane.showMessageDialog(this, "Failed to save the identity provider: " + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
