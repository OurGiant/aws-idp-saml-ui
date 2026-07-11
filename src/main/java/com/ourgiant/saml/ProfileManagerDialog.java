package com.ourgiant.saml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

/**
 * Dialog for listing, adding, editing, and deleting AWS profiles in ~/.aws/samlsts.
 */
public class ProfileManagerDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(ProfileManagerDialog.class);

    private final ConfigManager configManager;
    private final DefaultListModel<String> profileListModel = new DefaultListModel<>();
    private final JList<String> profileList = new JList<>(profileListModel);
    private boolean profilesChanged = false;

    public ProfileManagerDialog(Frame parent, ConfigManager configManager) {
        super(parent, "Manage Profiles", true);
        this.configManager = configManager;

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
        add(new JScrollPane(profileList), BorderLayout.CENTER);

        JPanel sideButtonPanel = new JPanel();
        sideButtonPanel.setLayout(new BoxLayout(sideButtonPanel, BoxLayout.Y_AXIS));

        JButton addButton = new JButton("Add...");
        JButton editButton = new JButton("Edit...");
        JButton deleteButton = new JButton("Delete");
        addButton.addActionListener(e -> onAdd());
        editButton.addActionListener(e -> onEdit());
        deleteButton.addActionListener(e -> onDelete());

        for (JButton button : new JButton[]{addButton, editButton, deleteButton}) {
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
            sideButtonPanel.add(button);
            sideButtonPanel.add(Box.createVerticalStrut(6));
        }
        sideButtonPanel.add(Box.createVerticalGlue());

        add(sideButtonPanel, BorderLayout.EAST);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Close");
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
        if (configManager.getIdpProviders().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No identity providers are configured. Add one to ~/.aws/samlsts before creating a profile.",
                "No Identity Providers",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        ProfileEditDialog dialog = new ProfileEditDialog((Frame) getParent(), configManager, null);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            profilesChanged = true;
            loadProfileList();
        }
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
