package com.ourgiant.saml.gui;

import com.ourgiant.saml.core.ConfigManager;

import org.ini4j.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Lists the identity providers ({@code [Fed-*]} sections) in ~/.aws/samlsts and adds, edits,
 * duplicates and deletes them, so they no longer have to be hand-edited (#249).
 */
public class IdentityProvidersDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(IdentityProvidersDialog.class);

    private final ConfigManager configManager;
    private final Supplier<ImportRolesDialog> importDialogFactory;
    private final ProvidersTableModel tableModel = new ProvidersTableModel();
    private final JTable table = new JTable(tableModel);
    private final JComboBox<String> defaultCombo = new JComboBox<>();
    private boolean loadingDefault;
    private boolean changed;

    /** @param importDialogFactory creates the "Import from IdP" dialog; null hides the Import Roles button */
    public IdentityProvidersDialog(Window parent, ConfigManager configManager, Supplier<ImportRolesDialog> importDialogFactory) {
        super(parent, "Identity Providers", ModalityType.APPLICATION_MODAL);
        this.configManager = configManager;
        this.importDialogFactory = importDialogFactory;

        initializeUI();
        reload(null);
        pack();
        setMinimumSize(new Dimension(560, 340));
        setLocationRelativeTo(parent);
    }

    /** True if providers, or the profiles that use them, changed while this dialog was open. */
    public boolean isChanged() {
        return changed;
    }

    private void initializeUI() {
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.rowAtPoint(e.getPoint()) >= 0) {
                    onEdit();
                }
            }
        });
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(450, 200));
        add(scrollPane, BorderLayout.CENTER);

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        JButton addButton = sideButton("Add...", KeyEvent.VK_A, "Add an identity provider", e -> onAdd());
        JButton editButton = sideButton("Edit...", KeyEvent.VK_E, "Edit or rename the selected identity provider", e -> onEdit());
        JButton duplicateButton = sideButton("Duplicate...", KeyEvent.VK_U,
            "Start a new identity provider from a copy of the selected one", e -> onDuplicate());
        JButton deleteButton = sideButton("Delete...", KeyEvent.VK_D, "Delete the selected identity provider", e -> onDelete());
        JButton importButton = sideButton("Import Roles...", KeyEvent.VK_I,
            "Log in to the selected identity provider and create profiles from the AWS roles it grants you", e -> onImport());
        importButton.setVisible(importDialogFactory != null);
        for (JButton button : new JButton[]{addButton, editButton, duplicateButton, deleteButton}) {
            side.add(button);
            side.add(Box.createVerticalStrut(6));
        }
        side.add(Box.createVerticalStrut(8));
        side.add(importButton);
        side.add(Box.createVerticalGlue());
        add(side, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel defaultRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel defaultLabel = new JLabel("Default for new profiles:");
        defaultLabel.setDisplayedMnemonic(KeyEvent.VK_F);
        defaultLabel.setLabelFor(defaultCombo);
        defaultCombo.setToolTipText("The identity provider Add Profile starts with. Existing profiles keep the provider they use.");
        defaultCombo.addActionListener(e -> onDefaultChanged());
        defaultRow.add(defaultLabel);
        defaultRow.add(defaultCombo);
        bottom.add(defaultRow, BorderLayout.WEST);
        JPanel closeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton closeButton = new JButton("Close");
        closeButton.setMnemonic(KeyEvent.VK_C);
        closeButton.addActionListener(e -> setVisible(false));
        closeRow.add(closeButton);
        bottom.add(closeRow, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> setVisible(false),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private static JButton sideButton(String text, int mnemonic, String tooltip, java.awt.event.ActionListener action) {
        JButton button = new JButton(text);
        button.setMnemonic(mnemonic);
        button.setToolTipText(tooltip);
        button.addActionListener(action);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
        return button;
    }

    /** Reloads the table and default list, then selects {@code select} (or keeps the current selection). */
    private void reload(String select) {
        String keep = select != null ? select : selectedSection();
        List<Row> rows = new ArrayList<>();
        for (String section : configManager.getIdpProviders()) {
            Profile.Section provider = configManager.getProvider(section);
            String loginPage = provider != null ? provider.getOrDefault("loginpage", "") : "";
            rows.add(new Row(section, host(loginPage), configManager.getProfilesUsingProvider(section).size()));
        }
        tableModel.setRows(rows);

        loadingDefault = true;
        try {
            defaultCombo.removeAllItems();
            for (Row row : rows) {
                defaultCombo.addItem(ConfigManager.awsProviderName(row.section()));
            }
            String current = configManager.getDefaultProvider();
            defaultCombo.setSelectedItem(current != null ? ConfigManager.awsProviderName(current) : null);
            defaultCombo.setEnabled(!rows.isEmpty());
        } finally {
            loadingDefault = false;
        }

        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).section().equals(keep)) {
                index = i;
            }
        }
        if (index < 0 && !rows.isEmpty()) {
            index = 0;
        }
        if (index >= 0) {
            table.setRowSelectionInterval(index, index);
        }
    }

    /** {@code https://acme.okta.com/app/...} → {@code acme.okta.com}; the raw text if it isn't a URL. */
    static String host(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host != null ? host : url;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private String selectedSection() {
        int row = table.getSelectedRow();
        return row >= 0 ? tableModel.rows.get(table.convertRowIndexToModel(row)).section() : null;
    }

    private String requireSelection(String action) {
        String section = selectedSection();
        if (section == null) {
            JOptionPane.showMessageDialog(this, "Select an identity provider to " + action + ".",
                "No Identity Provider Selected", JOptionPane.WARNING_MESSAGE);
        }
        return section;
    }

    private void onAdd() {
        IdentityProviderEditDialog dialog = IdentityProviderEditDialog.forAdd(this, configManager);
        dialog.setVisible(true);
        afterEdit(dialog);
    }

    private void onEdit() {
        String section = requireSelection("edit");
        if (section == null) {
            return;
        }
        IdentityProviderEditDialog dialog = IdentityProviderEditDialog.forEdit(this, configManager, section);
        dialog.setVisible(true);
        afterEdit(dialog);
    }

    private void onDuplicate() {
        String section = requireSelection("duplicate");
        if (section == null) {
            return;
        }
        Set<String> names = new HashSet<>();
        configManager.getIdpProviders().forEach(s -> names.add(ConfigManager.awsProviderName(s)));
        String suggested = ProfileManagerDialog.suggestCopyName(ConfigManager.awsProviderName(section), names);
        IdentityProviderEditDialog dialog = IdentityProviderEditDialog.forDuplicate(this, configManager, section, suggested);
        dialog.setVisible(true);
        afterEdit(dialog);
    }

    private void afterEdit(IdentityProviderEditDialog dialog) {
        if (dialog.getSavedSection() == null) {
            return;
        }
        changed = true;
        // The first provider becomes the default, so Add Profile and first-time import have one to start from.
        if (configManager.getDefaultProvider() == null || !configManager.getIdpProviders().contains(configManager.getDefaultProvider())) {
            try {
                configManager.setDefaultProvider(dialog.getSavedSection());
            } catch (Exception ex) {
                logger.warn("Could not set the default identity provider", ex);
            }
        }
        reload(dialog.getSavedSection());
    }

    private void onDefaultChanged() {
        if (loadingDefault || defaultCombo.getSelectedItem() == null) {
            return;
        }
        String section = ConfigManager.providerSection((String) defaultCombo.getSelectedItem());
        if (section.equals(configManager.getDefaultProvider())) {
            return;
        }
        try {
            configManager.setDefaultProvider(section);
            changed = true;
        } catch (Exception ex) {
            logger.error("Failed to set the default identity provider", ex);
            JOptionPane.showMessageDialog(this, "Failed to set the default identity provider: " + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
        reload(null);
    }

    private void onDelete() {
        String section = requireSelection("delete");
        if (section == null) {
            return;
        }
        String name = ConfigManager.awsProviderName(section);
        List<String> usedBy = configManager.getProfilesUsingProvider(section);
        String reassignTo = null;

        if (usedBy.isEmpty()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                "Delete identity provider \"" + name + "\"? No profiles use it.",
                "Delete Identity Provider", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        } else {
            DeleteChoice choice = askWhatToDoWithProfiles(name, section, usedBy);
            if (choice == null) {
                return;
            }
            reassignTo = choice.reassignTo();
        }

        try {
            configManager.deleteProvider(section, reassignTo);
            changed = true;
            reload(null);
        } catch (Exception ex) {
            logger.error("Failed to delete identity provider {}", section, ex);
            JOptionPane.showMessageDialog(this, "Failed to delete the identity provider: " + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** {@code reassignTo} null means delete the profiles too. */
    private record DeleteChoice(String reassignTo) {
    }

    /** Asks whether to move the provider's profiles to another provider or delete them; null if cancelled. */
    private DeleteChoice askWhatToDoWithProfiles(String name, String section, List<String> usedBy) {
        List<String> others = new ArrayList<>();
        for (String other : configManager.getIdpProviders()) {
            if (!other.equals(section)) {
                others.add(ConfigManager.awsProviderName(other));
            }
        }

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.anchor = GridBagConstraints.WEST; gbc.insets = new Insets(2, 0, 2, 0);
        String profiles = usedBy.size() == 1 ? "1 profile" : usedBy.size() + " profiles";
        gbc.gridy = 0;
        panel.add(new JLabel("<html><b>" + profiles + (usedBy.size() == 1 ? " uses " : " use ") + escape(name) + ":</b> " + escape(summarize(usedBy)) + "</html>"), gbc);

        JRadioButton moveButton = new JRadioButton("Move them to another identity provider:");
        moveButton.setMnemonic(KeyEvent.VK_M);
        JComboBox<String> targetCombo = new JComboBox<>(others.toArray(new String[0]));
        JRadioButton deleteButton = new JRadioButton("Delete the profiles too");
        deleteButton.setMnemonic(KeyEvent.VK_D);
        ButtonGroup group = new ButtonGroup();
        group.add(moveButton);
        group.add(deleteButton);
        if (others.isEmpty()) {
            moveButton.setEnabled(false);
            moveButton.setText("Move them to another identity provider (there are no others)");
            targetCombo.setVisible(false);
            deleteButton.setSelected(true);
        } else {
            moveButton.setSelected(true);
        }
        moveButton.addItemListener(e -> targetCombo.setEnabled(moveButton.isSelected()));

        gbc.gridy = 1; gbc.insets = new Insets(10, 0, 2, 0);
        panel.add(moveButton, gbc);
        gbc.gridy = 2; gbc.insets = new Insets(2, 24, 2, 0);
        panel.add(targetCombo, gbc);
        gbc.gridy = 3; gbc.insets = new Insets(8, 0, 2, 0);
        panel.add(deleteButton, gbc);

        Object[] options = {"Delete " + name, "Cancel"};
        int result = JOptionPane.showOptionDialog(this, panel, "Delete Identity Provider",
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (result != 0) {
            return null;
        }
        if (moveButton.isSelected()) {
            return new DeleteChoice(ConfigManager.providerSection((String) targetCombo.getSelectedItem()));
        }
        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete " + name + " and its " + profiles + "? This cannot be undone.",
            "Delete Profiles", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return confirm == JOptionPane.YES_OPTION ? new DeleteChoice(null) : null;
    }

    /** Up to five names, then "and N more". */
    static String summarize(List<String> names) {
        if (names.size() <= 5) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, 5)) + " and " + (names.size() - 5) + " more";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void onImport() {
        String section = requireSelection("import roles from");
        if (section == null) {
            return;
        }
        ImportRolesDialog dialog = importDialogFactory.get();
        dialog.selectProvider(section);
        dialog.setVisible(true);
        if (dialog.getImportedCount() > 0) {
            changed = true;
            reload(section);
        }
    }

    private record Row(String section, String loginHost, int profileCount) {
    }

    private static class ProvidersTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"AWS provider name", "Login page", "Profiles"};
        private List<Row> rows = List.of();

        void setRows(List<Row> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            return switch (column) {
                case 0 -> ConfigManager.awsProviderName(row.section());
                case 1 -> row.loginHost();
                default -> row.profileCount();
            };
        }
    }
}
