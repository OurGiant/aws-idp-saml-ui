package com.ourgiant.saml.gui;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.KeyEvent;

/** Shows bundled release notes (#210): after an upgrade, or from Help → Release Notes for This Version. */
class WhatsNewDialog extends JDialog {

    WhatsNewDialog(Frame parent, String title, String notesHtml) {
        super(parent, title, true);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JEditorPane notes = new JEditorPane("text/html", "<html><body style='font-family: sans-serif'>" + notesHtml + "</body></html>");
        notes.setEditable(false);
        notes.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) {
                AboutDialog.openTrustedUrl(this, e.getURL().toString());
            }
        });
        notes.setCaretPosition(0);
        JScrollPane scrollPane = new JScrollPane(notes);
        Rectangle screen = getGraphicsConfiguration() != null
            ? getGraphicsConfiguration().getBounds() : new Rectangle(0, 0, 1280, 800);
        scrollPane.setPreferredSize(new Dimension(Math.min(620, screen.width - 80), Math.min(480, screen.height - 160)));
        add(scrollPane, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton allReleases = new JButton("View All Releases");
        allReleases.setMnemonic(KeyEvent.VK_V);
        allReleases.setToolTipText(WhatsNew.RELEASES_URL);
        allReleases.addActionListener(e -> AboutDialog.openTrustedUrl(this, WhatsNew.RELEASES_URL));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(allReleases);
        buttons.add(close);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(close);

        pack();
        setLocationRelativeTo(parent);
    }
}
