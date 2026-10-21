package com.ourgiant.saml.gui;

import org.junit.jupiter.api.Test;

import javax.swing.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderNameRendererTest {

    @Test
    void showsAwsProviderNameButKeepsTheSectionAsTheItem() {
        JList<String> list = new JList<>();
        JLabel shown = (JLabel) new ProviderNameRenderer().getListCellRendererComponent(list, "Fed-CorpOkta", 0, false, false);
        assertEquals("CorpOkta", shown.getText());

        JComboBox<String> combo = new JComboBox<>(new String[]{"Fed-CorpOkta"});
        combo.setRenderer(new ProviderNameRenderer());
        assertEquals("Fed-CorpOkta", combo.getSelectedItem(), "the saved value is still the section name");
    }
}
