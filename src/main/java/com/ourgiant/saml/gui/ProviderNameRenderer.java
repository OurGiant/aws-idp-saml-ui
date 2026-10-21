package com.ourgiant.saml.gui;

import com.ourgiant.saml.core.ConfigManager;

import javax.swing.*;
import java.awt.*;

/**
 * Shows identity provider sections ({@code Fed-CorpOkta}) by their AWS provider name ({@code CorpOkta})
 * in dropdowns (#259). The combo's items stay section names, so selecting and saving are unchanged.
 */
class ProviderNameRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        Object shown = value instanceof String section ? ConfigManager.awsProviderName(section) : value;
        return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
    }
}
