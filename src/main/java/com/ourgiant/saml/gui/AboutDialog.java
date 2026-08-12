package com.ourgiant.saml.gui;

import com.ourgiant.saml.util.JsonUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Properties;

/**
 * App name, version, and an update check against GitHub releases — laid out per the
 * java-swing-project-setup family standard (icon west, HTML body center, status/close south)
 * to match doc-scrubber's and kiro-control-panel's About dialogs.
 */
public class AboutDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(AboutDialog.class);

    /** Help > About: does its own live check, same as always. */
    public AboutDialog(Frame parent) {
        this(parent, null);
    }

    /**
     * @param knownNewerRelease if non-null (latestVersion, releaseUrl), an already-confirmed
     *                          newer release to render immediately instead of performing a live
     *                          GitHub check — used when the silent startup check already found
     *                          and confirmed an update. That silent path also makes the dialog
     *                          non-modal, so an update found in the background never blocks the
     *                          main window; Help > About (a deliberate click) stays modal, the
     *                          normal expectation for that kind of dialog.
     */
    public AboutDialog(Frame parent, String[] knownNewerRelease) {
        super(parent, "About AWS IDP SAML UI", knownNewerRelease == null);
        String currentVersion = resolveCurrentVersion();

        setLayout(new BorderLayout(12, 12));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JLabel iconLabel = new JLabel(loadAppIcon(48));
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        add(iconLabel, BorderLayout.WEST);

        JEditorPane note = new JEditorPane("text/html", buildHtml(currentVersion));
        note.setEditable(false);
        note.setOpaque(false);
        note.setBorder(null);
        JScrollPane scrollPane = new JScrollPane(note);
        scrollPane.setBorder(null);
        scrollPane.setPreferredSize(new Dimension(360, 130));
        add(scrollPane, BorderLayout.CENTER);

        JLabel updateLabel = new JLabel(knownNewerRelease != null ? "" : "Checking for updates...");
        updateLabel.setForeground(Color.GRAY);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        buttonPanel.add(closeButton);

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(updateLabel, BorderLayout.WEST);
        southPanel.add(buttonPanel, BorderLayout.EAST);
        add(southPanel, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(closeButton);

        if (knownNewerRelease != null) {
            applyUpdateAvailableLabel(updateLabel, knownNewerRelease[0], knownNewerRelease[1]);
        } else {
            SwingWorker<String[], Void> versionChecker = new SwingWorker<>() {
                @Override
                protected String[] doInBackground() {
                    return fetchLatestRelease();
                }

                @Override
                protected void done() {
                    try {
                        String[] release = get();
                        if (release != null) {
                            String latestTag = release[0];
                            String releaseUrl = release[1];
                            String latestVersion = latestTag.startsWith("v") ? latestTag.substring(1) : latestTag;
                            if (isNewerVersion(latestVersion, currentVersion)) {
                                applyUpdateAvailableLabel(updateLabel, latestVersion, releaseUrl);
                            } else {
                                updateLabel.setText("Up to date");
                                updateLabel.setForeground(new Color(0, 128, 0));
                            }
                        } else {
                            updateLabel.setText("Could not check for updates");
                        }
                    } catch (Exception e) {
                        Throwable cause = e.getCause();
                        updateLabel.setText(cause instanceof UpdateFetchSslException sslEx
                            ? sslEx.getMessage() : "Could not check for updates");
                        logger.debug("Version check failed", e);
                    }
                }
            };
            versionChecker.execute();
        }

        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(parent);
    }

    private static ImageIcon loadAppIcon(int size) {
        URL iconUrl = AboutDialog.class.getResource("/saml_swing_icon_small.png");
        if (iconUrl == null) {
            return new ImageIcon();
        }
        Image scaled = new ImageIcon(iconUrl).getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    private static String buildHtml(String currentVersion) {
        return """
            <html><body style="font-family: sans-serif;">
            <h2 style="margin-top: 0;">AWS IDP SAML UI</h2>
            <p>Version %s</p>
            <p>AWS SAML authentication client.</p>
            <p>&copy; OurGiant</p>
            </body></html>
            """.formatted(currentVersion);
    }

    private void applyUpdateAvailableLabel(JLabel updateLabel, String latestVersion, String releaseUrl) {
        // latestVersion comes straight from GitHub's releases API response, so escape it before it
        // goes into this Swing HTML label — a crafted tag (e.g. "999<img src=...>") would otherwise
        // render as live HTML, including fetching an attacker-chosen image URL every time this
        // dialog/startup check runs.
        updateLabel.setText("<html><a href=''>Version " + escapeHtml(latestVersion) + " available — click to download</a></html>");
        updateLabel.setForeground(new Color(0, 102, 204));
        updateLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        updateLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    URI uri = new URI(releaseUrl);
                    if (!isTrustedReleaseUrl(uri)) {
                        logger.warn("Refusing to open untrusted release URL: {}", releaseUrl);
                        return;
                    }
                    Desktop.getDesktop().browse(uri);
                } catch (Exception ex) {
                    logger.warn("Could not open release URL in browser", ex);
                }
            }
        });
    }

    /**
     * Defense in depth, not a response to a live exploit: {@code uri} comes straight from
     * GitHub's releases API response, so a tampered response (only possible with an existing
     * TLS MITM position) could otherwise point this at an arbitrary URI/scheme. Restrict to
     * exactly the host the API is expected to point back to.
     */
    static boolean isTrustedReleaseUrl(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "github.com".equalsIgnoreCase(uri.getHost());
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String resolveCurrentVersion() {
        String version = AboutDialog.class.getPackage() != null
            ? AboutDialog.class.getPackage().getImplementationVersion() : null;
        if (version == null) {
            version = readVersionFromPropertiesResource();
        }
        return version != null ? version : "unknown";
    }

    private static String readVersionFromPropertiesResource() {
        try (InputStream in = AboutDialog.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                return props.getProperty("version");
            }
        } catch (IOException e) {
            logger.debug("Could not read version.properties", e);
        }
        return null;
    }

    static String[] fetchLatestRelease() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/OurGiant/aws-idp-saml-ui/releases/latest"))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "aws-idp-saml-ui")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                String tagName = JsonUtil.extractJsonString(body, "tag_name");
                String htmlUrl = JsonUtil.extractJsonString(body, "html_url");
                if (tagName != null && htmlUrl != null) {
                    return new String[]{tagName, htmlUrl};
                }
            }
        } catch (javax.net.ssl.SSLHandshakeException e) {
            logger.warn("TLS handshake failed fetching latest release from GitHub (possible TLS-inspecting proxy)", e);
            throw new UpdateFetchSslException(
                "Couldn't verify the secure connection (possible corporate network proxy)", e);
        } catch (Exception e) {
            logger.debug("Failed to fetch latest release from GitHub", e);
        }
        return null;
    }

    /** Lets the update-check UI distinguish "TLS handshake failed" from any other fetch failure. */
    private static class UpdateFetchSslException extends RuntimeException {
        UpdateFetchSslException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static boolean isNewerVersion(String latest, String current) {
        try {
            String[] latestParts = latest.split("\\.");
            String[] currentParts = current.split("\\.");
            int len = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < len; i++) {
                int l = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                int c = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (l > c) return true;
                if (l < c) return false;
            }
        } catch (NumberFormatException e) {
            logger.debug("Could not compare versions: {} vs {}", latest, current);
        }
        return false;
    }
}
