package com.ourgiant.saml.gui;

import com.ourgiant.saml.core.ConfigManager;
import com.ourgiant.saml.core.CredentialRequestError;
import com.ourgiant.saml.core.DiscoveredRoles;
import com.ourgiant.saml.core.RoleImportPlanner;
import com.ourgiant.saml.core.RoleImportPlanner.Candidate;
import com.ourgiant.saml.core.SamlAuthenticator;
import com.ourgiant.saml.core.SamlRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Logs in to an identity provider once and lists every AWS role the SAML assertion offers, so
 * profiles can be created by ticking roles instead of looking up account numbers and role names
 * by hand (#194).
 */
public class ImportRolesDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(ImportRolesDialog.class);

    private final ConfigManager configManager;
    private final Supplier<SamlAuthenticator> authenticatorFactory;
    private final boolean useFastPass;

    private final JComboBox<String> providerCombo;
    private final JCheckBox showBrowserCheckBox = new JCheckBox("Show browser");
    private final JButton findRolesButton = new JButton("Log In and Find Roles");
    private final JButton cancelLoginButton = new JButton("Cancel");
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel statusLabel = new JLabel(" ");
    private final RolesTableModel tableModel = new RolesTableModel();
    private final JTable table = new JTable(tableModel);
    private final JButton importButton = new JButton("Import Selected");

    private volatile SamlAuthenticator activeAuthenticator;
    private String discoveredProvider;
    private Map<String, String> discoveredAliases = Map.of();
    private int importedCount;

    public ImportRolesDialog(Frame parent, ConfigManager configManager, Supplier<SamlAuthenticator> authenticatorFactory,
                             boolean useFastPass, boolean showBrowser) {
        super(parent, "Import Profiles from Identity Provider", true);
        this.configManager = configManager;
        this.authenticatorFactory = authenticatorFactory;
        this.useFastPass = useFastPass;
        this.providerCombo = new JComboBox<>(configManager.getIdpProviders().toArray(new String[0]));
        showBrowserCheckBox.setSelected(showBrowser);

        initializeUI();
        pack();
        setMinimumSize(new Dimension(760, 420));
        setLocationRelativeTo(parent);
    }

    /** Number of profiles created; 0 if the dialog was closed without importing. */
    public int getImportedCount() {
        return importedCount;
    }

    private void initializeUI() {
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });

        JPanel loginRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel providerLabel = new JLabel("Identity provider:");
        providerLabel.setDisplayedMnemonic(KeyEvent.VK_P);
        providerLabel.setLabelFor(providerCombo);
        providerCombo.setToolTipText("The identity provider to log in to; every AWS role it grants you is listed below");
        loginRow.add(providerLabel);
        loginRow.add(providerCombo);
        showBrowserCheckBox.setMnemonic(KeyEvent.VK_B);
        showBrowserCheckBox.setToolTipText("Show the browser window during login, e.g. to complete an MFA prompt");
        loginRow.add(showBrowserCheckBox);
        findRolesButton.setMnemonic(KeyEvent.VK_L);
        findRolesButton.addActionListener(e -> findRoles());
        loginRow.add(findRolesButton);
        cancelLoginButton.addActionListener(e -> cancelLogin());
        cancelLoginButton.setVisible(false);
        loginRow.add(cancelLoginButton);

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(120, 14));
        JPanel statusRow = new JPanel(new BorderLayout(6, 0));
        statusRow.add(statusLabel, BorderLayout.CENTER);
        statusRow.add(progressBar, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(loginRow, BorderLayout.NORTH);
        top.add(statusRow, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(table.getRowHeight(), 22));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        // Click a header to sort (#218). The JTable maps view rows to model rows for editing, and
        // everything else here reads the model, so sorting never changes which role a tick applies to.
        table.setAutoCreateRowSorter(true);
        int[] widths = {60, 70, 110, 140, 170, 190, 190, 260};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getModel().addTableModelListener(e -> updateImportButton());
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(980, 320));
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel selectionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton selectAll = new JButton("Select All");
        selectAll.setMnemonic(KeyEvent.VK_A);
        selectAll.addActionListener(e -> tableModel.selectAll(true));
        JButton selectNone = new JButton("Select None");
        selectNone.setMnemonic(KeyEvent.VK_N);
        selectNone.addActionListener(e -> tableModel.selectAll(false));
        selectionButtons.add(selectAll);
        selectionButtons.add(selectNone);
        bottom.add(selectionButtons, BorderLayout.WEST);

        JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton closeButton = new JButton("Close");
        closeButton.setMnemonic(KeyEvent.VK_C);
        closeButton.addActionListener(e -> close());
        importButton.setMnemonic(KeyEvent.VK_I);
        importButton.addActionListener(e -> importSelected());
        actionButtons.add(closeButton);
        actionButtons.add(importButton);
        bottom.add(actionButtons, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        updateImportButton();
        statusLabel.setText("Log in to list the AWS roles your identity provider grants you.");
    }

    private void findRoles() {
        String provider = (String) providerCombo.getSelectedItem();
        if (provider == null) {
            return;
        }
        SamlAuthenticator authenticator = authenticatorFactory.get();
        activeAuthenticator = authenticator;
        setLoginInProgress(true);
        statusLabel.setText("Starting login to " + provider + "...");
        boolean showBrowser = showBrowserCheckBox.isSelected();

        new SwingWorker<DiscoveredRoles, Void>() {
            @Override
            protected DiscoveredRoles doInBackground() throws Exception {
                return authenticator.discoverRoles(provider, useFastPass, showBrowser,
                    msg -> SwingUtilities.invokeLater(() -> statusLabel.setText(msg)));
            }

            @Override
            protected void done() {
                boolean cancelledByUser = activeAuthenticator == null;
                activeAuthenticator = null;
                setLoginInProgress(false);
                if (cancelledByUser) {
                    statusLabel.setText("Login cancelled.");
                    return;
                }
                try {
                    DiscoveredRoles discovered = get();
                    showRoles(provider, discovered.roles(), discovered.accountAliases());
                } catch (Exception ex) {
                    CredentialRequestError error = CredentialRequestError.classify(ex);
                    statusLabel.setText(error.statusMessage());
                    JOptionPane.showMessageDialog(ImportRolesDialog.this, error.htmlMessage(), error.title(),
                        error.iconMessageType());
                }
            }
        }.execute();
    }

    void showRoles(String provider, List<SamlRole> roles) {
        showRoles(provider, roles, Map.of());
    }

    /** @param aliases account number to alias, when AWS's role-selection page showed them (#244) */
    void showRoles(String provider, List<SamlRole> roles, Map<String, String> aliases) {
        Map<String, RoleImportPlanner.ExistingProfile> existing = new LinkedHashMap<>();
        for (String profile : configManager.getAvailableProfiles()) {
            existing.put(profile, new RoleImportPlanner.ExistingProfile(
                configManager.getAccountNumber(profile), configManager.getIamRole(profile)));
        }
        List<Candidate> candidates = RoleImportPlanner.plan(roles, provider, existing, aliases);
        discoveredProvider = provider;
        discoveredAliases = aliases;
        tableModel.setCandidates(candidates, provider);

        long importable = candidates.stream().filter(Candidate::importable).count();
        if (candidates.isEmpty()) {
            statusLabel.setText("The identity provider didn't return any AWS roles for you.");
        } else {
            statusLabel.setText(candidates.size() + (candidates.size() == 1 ? " role" : " roles") + " found, "
                + importable + " not yet configured. Tick the ones to import; names can be edited.");
        }
    }

    private void cancelLogin() {
        SamlAuthenticator authenticator = activeAuthenticator;
        activeAuthenticator = null;
        if (authenticator != null) {
            statusLabel.setText("Cancelling login...");
            authenticator.cancel();
        }
    }

    private void setLoginInProgress(boolean inProgress) {
        findRolesButton.setEnabled(!inProgress);
        providerCombo.setEnabled(!inProgress);
        showBrowserCheckBox.setEnabled(!inProgress);
        cancelLoginButton.setVisible(inProgress);
        progressBar.setVisible(inProgress);
        updateImportButton();
    }

    private void updateImportButton() {
        int selected = tableModel.selectedRows().size();
        importButton.setText(selected > 0 ? "Import Selected (" + selected + ")" : "Import Selected");
        importButton.setEnabled(selected > 0 && activeAuthenticator == null);
    }

    private void importSelected() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        List<RolesTableModel.Row> rows = tableModel.selectedRows();
        List<RoleImportPlanner.Choice> choices = new ArrayList<>();
        for (RolesTableModel.Row row : rows) {
            choices.add(new RoleImportPlanner.Choice(row.profileName, row.guiName));
        }
        String problem = RoleImportPlanner.validate(choices, new HashSet<>(configManager.getAvailableProfiles()));
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "Can't Import", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> imported = new ArrayList<>();
        for (RolesTableModel.Row row : rows) {
            String name = row.profileName.trim();
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("guiname", row.guiName.trim());
            fields.put("accountnumber", row.candidate.role().accountNumber());
            fields.put("iamrole", row.candidate.role().roleName());
            fields.put("samlprovider", discoveredProvider);
            try {
                configManager.saveProfile(name, fields);
                imported.add(name);
            } catch (Exception ex) {
                logger.error("Failed to import profile {}", name, ex);
                importedCount = imported.size();
                JOptionPane.showMessageDialog(this,
                    "Imported " + imported.size() + " of " + rows.size() + " profiles before failing on \""
                        + name + "\":\n" + ex.getMessage(), "Import Failed", JOptionPane.ERROR_MESSAGE);
                showRoles(discoveredProvider, tableModel.roles(), discoveredAliases);
                return;
            }
        }
        importedCount = imported.size();
        logger.info("Imported {} profile(s) via {}: {}", imported.size(), discoveredProvider, imported);
        JOptionPane.showMessageDialog(this,
            "Imported " + imported.size() + (imported.size() == 1 ? " profile" : " profiles") + ":\n" + String.join(", ", imported),
            "Profiles Imported", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    private void close() {
        cancelLogin();
        dispose();
    }

    private static final class RolesTableModel extends AbstractTableModel {
        // Named so a new column can't silently shift the others (see #206).
        static final int IMPORT = 0, PRESENT = 1, ACCOUNT = 2, ALIAS = 3, ROLE = 4, PROFILE_NAME = 5, GUI_NAME = 6, NOTES = 7;
        private static final String[] COLUMNS = {"Import", "Present", "Account", "Alias", "Role", "Profile Name", "GUI Name", "Notes"};

        static final class Row {
            final Candidate candidate;
            final String notes;
            boolean selected;
            String profileName;
            String guiName;

            Row(Candidate candidate, String notes) {
                this.candidate = candidate;
                this.notes = notes;
                this.profileName = candidate.suggestedProfileName() != null ? candidate.suggestedProfileName() : "";
                this.guiName = candidate.suggestedGuiName();
            }
        }

        private final List<Row> rows = new ArrayList<>();

        void setCandidates(List<Candidate> candidates, String provider) {
            rows.clear();
            String expected = provider.startsWith("Fed-") ? provider.substring(4) : provider;
            for (Candidate candidate : candidates) {
                String notes = "";
                if (candidate.existingProfile() != null) {
                    notes = "Already configured as \"" + candidate.existingProfile() + "\"";
                } else if (candidate.awsProviderMismatch() != null) {
                    notes = "AWS SAML provider is \"" + candidate.awsProviderMismatch() + "\", not \"" + expected
                        + "\" — add a Fed-" + candidate.awsProviderMismatch() + " provider to use this role";
                }
                rows.add(new Row(candidate, notes));
            }
            fireTableDataChanged();
        }

        List<SamlRole> roles() {
            return rows.stream().map(r -> r.candidate.role()).toList();
        }

        List<Row> selectedRows() {
            return rows.stream().filter(r -> r.selected).toList();
        }

        void selectAll(boolean selected) {
            for (Row row : rows) {
                if (row.candidate.importable()) {
                    row.selected = selected;
                }
            }
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
            return column == IMPORT ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int column) {
            return rows.get(rowIndex).candidate.importable()
                && (column == IMPORT || column == PROFILE_NAME || column == GUI_NAME);
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            return switch (column) {
                case IMPORT -> row.selected;
                case PRESENT -> row.candidate.existingProfile() != null ? "Yes" : "No";
                case ACCOUNT -> row.candidate.role().accountNumber();
                case ALIAS -> row.candidate.accountAlias() != null ? row.candidate.accountAlias() : "";
                case ROLE -> row.candidate.role().roleName();
                case PROFILE_NAME -> row.profileName;
                case GUI_NAME -> row.guiName;
                default -> row.notes;
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int column) {
            if (!isCellEditable(rowIndex, column)) {
                return; // already-configured / unusable roles can never be selected or renamed
            }
            Row row = rows.get(rowIndex);
            switch (column) {
                case IMPORT -> row.selected = Boolean.TRUE.equals(value);
                case PROFILE_NAME -> row.profileName = value == null ? "" : value.toString();
                case GUI_NAME -> row.guiName = value == null ? "" : value.toString();
                default -> {
                    return;
                }
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }
}
