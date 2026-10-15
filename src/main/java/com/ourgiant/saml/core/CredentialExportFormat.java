package com.ourgiant.saml.core;

import java.util.Locale;

/**
 * Shell syntaxes the plaintext credentials can be rendered in, so a copy/paste into a terminal
 * sets the variables first time. Values are quoted for each shell: secret keys and session
 * tokens routinely contain {@code /}, {@code +} and {@code =}, and quoting also keeps any
 * unexpected metacharacters from being interpreted.
 */
public enum CredentialExportFormat {
    BASH("bash / zsh") {
        @Override
        String line(String name, String value) {
            // Single quotes are fully literal in POSIX shells; an embedded ' is closed, escaped, reopened.
            return "export " + name + "='" + value.replace("'", "'\\''") + "'";
        }
    },
    POWERSHELL("PowerShell") {
        @Override
        String line(String name, String value) {
            // Single-quoted strings don't expand $ or backticks; an embedded ' is doubled.
            return "$env:" + name + " = '" + value.replace("'", "''") + "'";
        }
    },
    CMD("Command Prompt (cmd)") {
        @Override
        String line(String name, String value) {
            // Quoting the whole assignment keeps & | < > ^ literal without adding quotes to the value.
            return "set \"" + name + "=" + value + "\"";
        }
    },
    DOTENV("Plain KEY=value (.env)") {
        @Override
        String line(String name, String value) {
            return name + "=" + value;
        }
    };

    private final String label;

    CredentialExportFormat(String label) {
        this.label = label;
    }

    abstract String line(String name, String value);

    public String format(String accessKeyId, String secretAccessKey, String sessionToken) {
        String nl = System.lineSeparator();
        return line("AWS_ACCESS_KEY_ID", accessKeyId) + nl
            + line("AWS_SECRET_ACCESS_KEY", secretAccessKey) + nl
            + line("AWS_SESSION_TOKEN", sessionToken) + nl;
    }

    /** The native shell for the given {@code os.name}: PowerShell on Windows, bash/zsh elsewhere. */
    public static CredentialExportFormat defaultFor(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows") ? POWERSHELL : BASH;
    }

    /** The format with this {@link #name()}, or the platform default for a missing/unknown value. */
    public static CredentialExportFormat fromName(String name, String osName) {
        if (name != null) {
            for (CredentialExportFormat format : values()) {
                if (format.name().equals(name)) {
                    return format;
                }
            }
        }
        return defaultFor(osName);
    }

    @Override
    public String toString() {
        return label;
    }
}
