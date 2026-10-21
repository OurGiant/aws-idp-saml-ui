package com.ourgiant.saml.gui;

import com.ourgiant.saml.ThemeManager;
import com.ourgiant.saml.core.ConfigManager;
import com.ourgiant.saml.core.DatabaseManager;
import com.ourgiant.saml.core.ProfileBundle;
import com.ourgiant.saml.core.ProfileBundleImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Previews a profile export file and imports the chosen profiles (and optionally app settings)
 * from it (#199). New profiles are pre-selected; ones whose name already exists here are not, and
 * selecting one means overwriting it (confirmed before applying) unless "Import As" is changed.
 */
public class ProfileImportDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(ProfileImportDialog.class);

    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final ProfileBundle bundle;
    private final Set<String> existingProfiles;
    private final ProfilesTableModel tableModel;
    private final JTable table;
    private final JCheckBox importSettingsCheckBox = new JCheckBox("Also import app settings and pinned profiles");
    private final JButton importButton = new JButton("Import");
    private ProfileBundleImporter.Result result;

    public ProfileImportDialog(Frame parent, ConfigManager configManager, DatabaseManager databaseManager,
                               ProfileBundle bundle, Path file) {
        super(parent, "Import Profiles", true);
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.bundle = bundle;
        this.existingProfiles = new HashSet<>(configManager.getAvailableProfiles());
        this.tableModel = new ProfilesTableModel(bundle.profiles(), existingProfiles);
        this.table = new JTable(tableModel);

        initializeUI(file);
        pack();
        setMinimumSize(new Dimension(720, 420));
        setLocationRelativeTo(parent);
    }

    /** What the import did, or null if the dialog was closed without importing. */
    public ProfileBundleImporter.Result getResult() {
        return result;
    }

    private void initializeUI(Path file) {
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        String exported = bundle.exportedAt().isEmpty() ? "" : " on " + bundle.exportedAt().replace('T', ' ').replaceAll("\\.\\d+Z$", " UTC");
        JLabel header = new JLabel("<html><b>" + escape(file.getFileName().toString()) + "</b> — exported by version "
            + escape(bundle.appVersion()) + escape(exported) + ". It contains no credentials or passwords.</html>");
        add(header, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(table.getRowHeight(), 22));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setAutoCreateRowSorter(true); // click a header to sort (#218); edits map view rows to model rows
        int[] widths = {60, 170, 110, 150, 120, 170, 190};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        tableModel.addTableModelListener(e -> updateImportButton());
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(980, 260));

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(scrollPane, BorderLayout.CENTER);
        center.add(buildNotes(), BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        importSettingsCheckBox.setMnemonic(KeyEvent.VK_S);
        importSettingsCheckBox.setSelected(!bundle.settings().isEmpty() || !bundle.pinnedProfiles().isEmpty());
        importSettingsCheckBox.setEnabled(!bundle.settings().isEmpty() || !bundle.pinnedProfiles().isEmpty());
        importSettingsCheckBox.setToolTipText("Theme, browser, session duration, notifications and similar preferences, "
            + "plus pins for the imported profiles (added after your existing pins). Never passwords.");
        bottom.add(importSettingsCheckBox, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setMnemonic(KeyEvent.VK_C);
        cancelButton.addActionListener(e -> dispose());
        importButton.setMnemonic(KeyEvent.VK_I);
        importButton.addActionListener(e -> doImport());
        buttons.add(cancelButton);
        buttons.add(importButton);
        bottom.add(buttons, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
        updateImportButton();
    }

    /** {@code Fed-CorpOkta, Fed-Azure} → {@code CorpOkta, Azure} (#259). */
    private static String displayNames(java.util.Collection<String> providerSections) {
        return String.join(", ", providerSections.stream().map(ConfigManager::awsProviderName).toList());
    }

    private JComponent buildNotes() {
        ProfileBundleImporter.Preview preview = ProfileBundleImporter.preview(bundle, configManager.getAllSections());
        List<String> notes = new ArrayList<>();
        if (!preview.providersToAdd().isEmpty()) {
            notes.add("Identity providers to add: " + displayNames(preview.providersToAdd()));
        }
        if (!preview.providersKeptDifferent().isEmpty()) {
            notes.add("Kept your existing " + displayNames(preview.providersKeptDifferent())
                + " (the file's version differs; existing identity providers are never overwritten)");
        }
        if (!preview.globalKeysToAdd().isEmpty()) {
            notes.add("Global settings to add (only ones you don't have): " + String.join(", ", preview.globalKeysToAdd()));
        }
        StringBuilder html = new StringBuilder("<html>");
        for (String note : notes) {
            html.append("• ").append(escape(note)).append("<br>");
        }
        html.append("Tick the profiles to import. A profile whose name already exists is overwritten unless you change \"Import As\".</html>");
        return new JLabel(html.toString());
    }

    private void updateImportButton() {
        int selected = tableModel.choices().size();
        importButton.setText(selected > 0 ? "Import (" + selected + ")" : "Import");
        importButton.setEnabled(selected > 0);
    }

    private void doImport() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        List<ProfileBundleImporter.Choice> choices = tableModel.choices();
        List<String> overwrites = ProfileBundleImporter.overwrites(choices, existingProfiles);
        String problem = ProfileBundleImporter.validate(choices, existingProfiles, new HashSet<>(overwrites));
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "Can't Import", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!overwrites.isEmpty()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                "These existing profiles will be replaced by the file's version:\n" + String.join(", ", overwrites) + "\n\nContinue?",
                "Overwrite Profiles", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }

        try {
            result = ProfileBundleImporter.apply(bundle, choices, importSettingsCheckBox.isSelected(), configManager, databaseManager);
        } catch (Exception ex) {
            logger.error("Profile import failed", ex);
            JOptionPane.showMessageDialog(this, "Import failed: " + ex.getMessage(), "Import Failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (result.importedTheme() != null && !ThemeManager.applyTheme(result.importedTheme())) {
            logger.warn("Imported theme \"{}\" isn't available here; keeping the current theme", result.importedTheme());
        }
        logger.info("Imported profiles {} (overwrote {}), providers {}, global keys {}, settings applied: {}",
            result.importedProfiles(), result.overwrittenProfiles(), result.providersAdded(), result.globalKeysAdded(),
            result.settingsApplied());
        dispose();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class ProfilesTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Import", "Profile", "Account", "Role", "Identity Provider", "Import As", "Status"};

        private static final class Row {
            final String source;
            final Map<String, String> fields;
            boolean selected;
            String target;

            Row(String source, Map<String, String> fields, boolean selected) {
                this.source = source;
                this.fields = fields;
                this.selected = selected;
                this.target = source;
            }
        }

        private final List<Row> rows = new ArrayList<>();
        private final Set<String> existing;

        ProfilesTableModel(Map<String, Map<String, String>> profiles, Set<String> existing) {
            this.existing = existing;
            profiles.forEach((name, fields) -> rows.add(new Row(name, fields, !existing.contains(name))));
        }

        List<ProfileBundleImporter.Choice> choices() {
            return rows.stream().filter(r -> r.selected).map(r -> new ProfileBundleImporter.Choice(r.source, r.target)).toList();
        }

        private String status(Row row) {
            String target = row.target == null ? "" : row.target.trim();
            if (!existing.contains(target)) {
                return row.selected ? "New" : "New (not selected)";
            }
            return row.selected ? "Will overwrite existing" : "Exists here — skipped";
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
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0 || column == 5;
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            return switch (column) {
                case 0 -> row.selected;
                case 1 -> row.source;
                case 2 -> row.fields.getOrDefault("accountnumber", "");
                case 3 -> row.fields.getOrDefault("iamrole", "");
                case 4 -> row.fields.containsKey("samlprovider")
                    ? ConfigManager.awsProviderName(row.fields.get("samlprovider")) : "(default)";
                case 5 -> row.target;
                default -> status(row);
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            if (column == 0) {
                row.selected = Boolean.TRUE.equals(value);
            } else if (column == 5) {
                row.target = value == null ? "" : value.toString();
            } else {
                return;
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }
}
