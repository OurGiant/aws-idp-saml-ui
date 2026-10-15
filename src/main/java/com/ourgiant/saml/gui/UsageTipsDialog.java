package com.ourgiant.saml.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * One usage tip at a time with Previous/Next (#211), plus "Don't show tips on startup". Modal;
 * after it closes, {@link #isShowOnStartup()} and {@link #getNextIndex()} say what to persist.
 */
class UsageTipsDialog extends JDialog {

    private final List<UsageTips.Tip> tips;
    private final JLabel counterLabel = new JLabel();
    private final JEditorPane body = new JEditorPane("text/html", "");
    private final JButton previousButton = new JButton("Previous");
    private final JButton nextButton = new JButton("Next");
    private final JCheckBox dontShowCheckBox = new JCheckBox("Don't show tips on startup");
    private int index;

    UsageTipsDialog(Frame parent, List<UsageTips.Tip> tips, int startIndex, boolean showOnStartup) {
        super(parent, "Usage Tips", true);
        this.tips = tips;
        this.index = UsageTips.startIndex(startIndex, tips.size());

        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        counterLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        add(counterLabel, BorderLayout.NORTH);

        body.setEditable(false);
        body.setOpaque(false);
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        body.setFont(UIManager.getFont("Label.font"));
        JScrollPane scrollPane = new JScrollPane(body);
        scrollPane.setBorder(null);
        scrollPane.setPreferredSize(new Dimension(460, 150));
        add(scrollPane, BorderLayout.CENTER);

        dontShowCheckBox.setMnemonic(KeyEvent.VK_D);
        dontShowCheckBox.setSelected(!showOnStartup);
        dontShowCheckBox.setToolTipText("You can always open tips again from Help → Usage Tips");
        previousButton.setMnemonic(KeyEvent.VK_P);
        previousButton.addActionListener(e -> show(index - 1));
        nextButton.setMnemonic(KeyEvent.VK_N);
        nextButton.addActionListener(e -> show(index + 1));
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(previousButton);
        buttons.add(nextButton);
        buttons.add(closeButton);
        JPanel south = new JPanel(new BorderLayout());
        south.add(dontShowCheckBox, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(nextButton);

        show(index);
        pack();
        setLocationRelativeTo(parent);
    }

    private void show(int newIndex) {
        if (tips.isEmpty()) {
            counterLabel.setText(" ");
            body.setText("<html><body>No tips are available.</body></html>");
            previousButton.setEnabled(false);
            nextButton.setEnabled(false);
            return;
        }
        index = Math.floorMod(newIndex, tips.size());
        UsageTips.Tip tip = tips.get(index);
        counterLabel.setText("Tip " + (index + 1) + " of " + tips.size());
        body.setText("<html><body><p><b>" + tip.title() + "</b></p><p>" + tip.bodyHtml() + "</p></body></html>");
        body.setCaretPosition(0);
        previousButton.setEnabled(tips.size() > 1);
        nextButton.setEnabled(tips.size() > 1);
    }

    boolean isShowOnStartup() {
        return !dontShowCheckBox.isSelected();
    }

    /** Where the next opening should start: the tip after the one last shown. */
    int getNextIndex() {
        return tips.isEmpty() ? 0 : (index + 1) % tips.size();
    }
}
