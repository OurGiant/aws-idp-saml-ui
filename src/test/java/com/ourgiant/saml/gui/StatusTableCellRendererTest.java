package com.ourgiant.saml.gui;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.Color;
import java.awt.Component;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusTableCellRendererTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void statusColorFollowsColumnWhenReordered(int viewPosition) {
        DefaultTableModel model = new DefaultTableModel(
            new String[]{"Profile", "Account Number", "Status"}, 0);
        model.addRow(new Object[]{"production", "123456789012", "VALID"});
        JTable table = new JTable(model);
        table.getColumnModel().moveColumn(2, viewPosition);

        SwingMain.StatusTableCellRenderer renderer = new SwingMain.StatusTableCellRenderer(Set.of(), List.of());
        Component cell = renderer.getTableCellRendererComponent(
            table, table.getValueAt(0, viewPosition), false, false, 0, viewPosition);

        assertEquals(new Color(0, 128, 0), cell.getForeground());
    }
}