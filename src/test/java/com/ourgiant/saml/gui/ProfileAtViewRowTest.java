package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileAtViewRowTest {

    @Test
    void readsTheProfileRegardlessOfColumnOrderAndSorting() {
        DefaultTableModel model = new DefaultTableModel(
            new String[]{"Profile", "Account Number", "Status", "Expires At", "Time Remaining", "GUI Name"}, 0);
        model.addRow(new Object[]{"zeta", "111111111111", "VALID", "", "", "Zeta"});
        model.addRow(new Object[]{"alpha", "222222222222", "EXPIRED", "", "", "Alpha"});
        JTable table = new JTable(model);
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);

        table.moveColumn(1, 0); // Account Number dragged in front of Profile (#232)
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING))); // by Profile: alpha, then zeta

        assertEquals("Account Number", table.getColumnName(0));
        assertEquals("222222222222", table.getValueAt(0, 0), "view column 0 is no longer Profile");
        assertEquals("alpha", SwingMain.profileAtViewRow(table, 0));
        assertEquals("zeta", SwingMain.profileAtViewRow(table, 1));
    }
}
