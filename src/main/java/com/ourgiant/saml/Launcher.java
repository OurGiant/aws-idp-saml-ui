package com.ourgiant.saml;

import com.ourgiant.saml.core.CredentialProcess;

import java.io.PrintStream;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * The jar's entry point. {@code --credential-process <profile>} (#222) runs the AWS
 * credential_process mode without ever loading the GUI; anything else starts the app as before.
 * Kept free of Swing and logging imports so the CLI path starts fast and prints only JSON.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && CredentialProcess.FLAG.equals(args[0])) {
            System.exit(runCredentialProcess(args));
        }
        com.ourgiant.saml.gui.SwingMain.main(args);
    }

    /** Also used by {@code SwingMain.main} if it's launched directly with the flag. */
    public static int runCredentialProcess(String[] args) {
        // Libraries on the classpath (sqlite-jdbc) would start logback, whose normal config logs to stdout.
        System.setProperty("logback.configurationFile", "logback-cli.xml");
        System.setProperty("java.awt.headless", "true");
        // Until the JSON is printed, anything else written to stdout goes to stderr instead.
        PrintStream jsonOut = System.out;
        System.setOut(System.err);
        try {
            return CredentialProcess.run(args, Paths.get(System.getProperty("user.home")), Instant.now(), jsonOut, System.err);
        } finally {
            jsonOut.flush();
        }
    }
}
