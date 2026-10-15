package com.ourgiant.saml.gui;

import com.ourgiant.saml.util.LogPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Live-ish view of the app's own log files, since nothing else surfaces them without leaving the
 * app. Non-modal (so it can stay open alongside normal use) and auto-refreshes on a timer while
 * visible. Mirrors kiro-control-panel's LogViewerDialog, plus a selector for the startup log.
 */
public class LogViewerDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(LogViewerDialog.class);
    private static final int REFRESH_INTERVAL_MILLIS = 2000;
    /** Only the tail of a large log is shown, so a long-running session doesn't make each refresh slow. */
    static final int MAX_DISPLAY_BYTES = 1024 * 1024;

    private enum LogChoice {
        APPLICATION("Application log", LogPaths::currentLogFile),
        STARTUP("Startup log", LogPaths::startupLogFile);

        private final String label;
        private final Supplier<Path> path;

        LogChoice(String label, Supplier<Path> path) {
            this.label = label;
            this.path = path;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final JTextArea logArea = new JTextArea();
    private final JComboBox<LogChoice> logChoice = new JComboBox<>(LogChoice.values());
    private final JLabel pathLabel = new JLabel();
    private final Timer refreshTimer;
    private long lastLoadedFileSize = -1;

    public LogViewerDialog(Frame parent) {
        super(parent, "Logs", false);

        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel topPanel = new JPanel(new BorderLayout(8, 0));
        topPanel.add(logChoice, BorderLayout.WEST);
        topPanel.add(pathLabel, BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);
        logChoice.addActionListener(e -> reload(true));

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setPreferredSize(new Dimension(860, 480));
        add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> reload(true));
        JButton openFolderButton = new JButton("Open Log Folder...");
        openFolderButton.addActionListener(e -> openLogFolder());
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        buttonPanel.add(refreshButton);
        buttonPanel.add(openFolderButton);
        buttonPanel.add(closeButton);
        add(buttonPanel, BorderLayout.SOUTH);

        refreshTimer = new Timer(REFRESH_INTERVAL_MILLIS, e -> reload(false));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                refreshTimer.start();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                refreshTimer.stop();
            }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        reload(true);
        pack();
        setLocationRelativeTo(parent);
    }

    private Path selectedLogFile() {
        return ((LogChoice) logChoice.getSelectedItem()).path.get();
    }

    /** @param force reload even if the file size looks unchanged (Refresh button, switching logs) */
    private void reload(boolean force) {
        Path logFile = selectedLogFile();
        pathLabel.setText(logFile.toString());
        try {
            if (!Files.exists(logFile)) {
                lastLoadedFileSize = -1;
                logArea.setText("No log file yet — nothing has been written to " + logFile.getFileName() + ".");
                return;
            }
            long size = Files.size(logFile);
            if (!force && size == lastLoadedFileSize) {
                return; // nothing new since the last tick, skip the re-read/re-render
            }
            lastLoadedFileSize = size;
            logArea.setText(readTail(logFile, MAX_DISPLAY_BYTES));
            logArea.setCaretPosition(logArea.getDocument().getLength());
        } catch (IOException e) {
            logger.warn("Failed to read log file {}", logFile, e);
            logArea.setText("Failed to read log file: " + e.getMessage());
        }
    }

    /** Reads at most the last {@code maxBytes} of the file, starting at a line boundary when truncated. */
    static String readTail(Path file, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long start = Math.max(0, length - maxBytes);
            byte[] bytes = new byte[(int) (length - start)];
            raf.seek(start);
            raf.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (start > 0) {
                int firstNewline = text.indexOf('\n');
                text = "… (showing the last " + (maxBytes / 1024) + " KB) …\n"
                    + (firstNewline >= 0 ? text.substring(firstNewline + 1) : text);
            }
            return text;
        }
    }

    private void openLogFolder() {
        Path dir = LogPaths.logsDir();
        if (!Files.isDirectory(dir)) {
            JOptionPane.showMessageDialog(this,
                "That location doesn't exist yet:\n" + dir, "Not Found", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            JOptionPane.showMessageDialog(this,
                "Opening a file manager isn't supported on this platform.\nLocation: " + dir,
                "Not Supported", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().open(dir.toFile());
        } catch (IOException e) {
            logger.warn("Failed to open {} in file manager", dir, e);
            JOptionPane.showMessageDialog(this,
                "Failed to open: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
