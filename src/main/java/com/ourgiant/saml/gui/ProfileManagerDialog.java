package com.ourgiant.saml.gui;

import com.ourgiant.saml.core.ConfigManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Dialog for listing, adding, editing, and deleting AWS profiles in ~/.aws/samlsts.
 */
public class ProfileManagerDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(ProfileManagerDialog.class);

    private final ConfigManager configManager;
    private final DefaultListModel<String> profileListModel = new DefaultListModel<>();
    private final JList<String> profileList = new JList<>(profileListModel);
    private final Supplier<ImportRolesDialog> importDialogFactory;
    private boolean profilesChanged = false;

    public ProfileManagerDialog(Frame parent, ConfigManager configManager) {
        this(parent, configManager, null);
    }

    /** @param importDialogFactory creates the "Import from IdP" dialog; null hides that button */
    public ProfileManagerDialog(Frame parent, ConfigManager configManager, Supplier<ImportRolesDialog> importDialogFactory) {
        super(parent, "Manage Profiles", true);
        this.configManager = configManager;
        this.importDialogFactory = importDialogFactory;

        initializeUI();
        loadProfileList();
        pack();
        setMinimumSize(new Dimension(420, 320));
        setLocationRelativeTo(parent);
    }

    /**
     * True if any profile was added, edited, or deleted while this dialog was open.
     */
    public boolean isProfilesChanged() {
        return profilesChanged;
    }

    private void initializeUI() {
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.setToolTipText("Configured AWS profiles");
        add(new JScrollPane(profileList), BorderLayout.CENTER);

        JPanel sideButtonPanel = new JPanel();
        sideButtonPanel.setLayout(new BoxLayout(sideButtonPanel, BoxLayout.Y_AXIS));

        JButton addButton = new JButton("Add...");
        addButton.setMnemonic(KeyEvent.VK_A);
        addButton.setToolTipText("Add a new AWS profile");
        JButton editButton = new JButton("Edit...");
        editButton.setMnemonic(KeyEvent.VK_E);
        editButton.setToolTipText("Edit the selected profile");
        JButton deleteButton = new JButton("Delete");
        deleteButton.setMnemonic(KeyEvent.VK_D);
        deleteButton.setToolTipText("Delete the selected profile");
        JButton duplicateButton = new JButton("Duplicate...");
        duplicateButton.setMnemonic(KeyEvent.VK_U);
        duplicateButton.setToolTipText("Start a new profile from a copy of the selected one");
        JButton importButton = new JButton("Import from IdP...");
        importButton.setMnemonic(KeyEvent.VK_I);
        importButton.setToolTipText("Log in to an identity provider once and create profiles from the AWS roles it grants you");
        importButton.setVisible(importDialogFactory != null);
        JButton providersButton = new JButton("Identity Providers...");
        providersButton.setMnemonic(KeyEvent.VK_P);
        providersButton.setToolTipText("Add, edit or delete the identity providers profiles sign in through");
        providersButton.addActionListener(e -> showIdentityProviders());
        addButton.addActionListener(e -> onAdd());
        editButton.addActionListener(e -> onEdit());
        deleteButton.addActionListener(e -> onDelete());
        duplicateButton.addActionListener(e -> onDuplicate());
        importButton.addActionListener(e -> onImport());

        for (JButton button : new JButton[]{addButton, editButton, duplicateButton, deleteButton, importButton, providersButton}) {
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
            if (button == providersButton) {
                sideButtonPanel.add(Box.createVerticalStrut(8));
            }
            sideButtonPanel.add(button);
            sideButtonPanel.add(Box.createVerticalStrut(6));
        }
        sideButtonPanel.add(Box.createVerticalGlue());

        add(sideButtonPanel, BorderLayout.EAST);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Close");
        closeButton.setMnemonic(KeyEvent.VK_L);
        closeButton.addActionListener(e -> setVisible(false));
        bottomPanel.add(closeButton);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void loadProfileList() {
        String previouslySelected = profileList.getSelectedValue();
        profileListModel.clear();
        for (String profile : configManager.getAvailableProfiles()) {
            profileListModel.addElement(profile);
        }
        if (previouslySelected != null) {
            profileList.setSelectedValue(previouslySelected, true);
        }
    }

    private void onAdd() {
        if (!ensureIdentityProvider("creating a profile")) {
            return;
        }

        ProfileEditDialog dialog = new ProfileEditDialog((Frame) getParent(), configManager, null);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            profilesChanged = true;
            loadProfileList();
        }
    }

    private void onImport() {
        if (!ensureIdentityProvider("importing profiles")) {
            return;
        }

        ImportRolesDialog dialog = importDialogFactory.get();
        dialog.setVisible(true);
        if (dialog.getImportedCount() > 0) {
            profilesChanged = true;
            loadProfileList();
        }
    }

    /** Opens the Identity Providers screen (#249). */
    private void showIdentityProviders() {
        IdentityProvidersDialog dialog = new IdentityProvidersDialog(this, configManager, importDialogFactory);
        dialog.setVisible(true);
        if (dialog.isChanged()) {
            profilesChanged = true;
            loadProfileList();
        }
    }

    /**
     * True if at least one identity provider exists. Otherwise offers to add one on the
     * Identity Providers screen, and returns whether there is one afterwards.
     */
    private boolean ensureIdentityProvider(String beforeWhat) {
        if (!configManager.getIdpProviders().isEmpty()) {
            return true;
        }
        int choice = JOptionPane.showConfirmDialog(this,
            "No identity providers are set up yet. Add one before " + beforeWhat + "?",
            "No Identity Providers",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.INFORMATION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return false;
        }
        showIdentityProviders();
        return !configManager.getIdpProviders().isEmpty();
    }

    private void onEdit() {
        String selected = profileList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this,
                "Select a profile to edit.",
                "No Profile Selected",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        ProfileEditDialog dialog = new ProfileEditDialog((Frame) getParent(), configManager, selected);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            profilesChanged = true;
            loadProfileList();
        }
    }

    /** Opens Add Profile pre-filled from the selected profile, copying every key in its section (#229). */
    private void onDuplicate() {
        String selected = profileList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this,
                "Select a profile to duplicate.",
                "No Profile Selected",
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        Map<String, String> template = configManager.getAllSections().getOrDefault(selected, Map.of());
        String suggested = suggestCopyName(selected, new HashSet<>(configManager.getAvailableProfiles()));
        ProfileEditDialog dialog = ProfileEditDialog.forDuplicate((Frame) getParent(), configManager, template, suggested);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            profilesChanged = true;
            loadProfileList();
            profileList.setSelectedValue(dialog.getSavedProfileName(), true);
        }
    }

    /** {@code <name>-copy}, or {@code <name>-copy-2}, {@code -copy-3}... if that's taken. */
    static String suggestCopyName(String name, Set<String> existing) {
        String base = name + "-copy";
        if (!existing.contains(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            if (!existing.contains(base + "-" + n)) {
                return base + "-" + n;
            }
        }
    }

    private void onDelete() {
        String selected = profileList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this,
                "Select a profile to delete.",
                "No Profile Selected",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete profile \"" + selected + "\"? This cannot be undone.",
            "Delete Profile",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            configManager.deleteProfile(selected);
            profilesChanged = true;
            loadProfileList();
        } catch (Exception ex) {
            logger.error("Failed to delete profile: {}", selected, ex);
            JOptionPane.showMessageDialog(this,
                "Failed to delete profile: " + ex.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }
}
